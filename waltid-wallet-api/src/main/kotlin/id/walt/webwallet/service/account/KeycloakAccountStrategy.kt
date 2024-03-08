package id.walt.webwallet.service.account

import com.auth0.jwk.Jwk
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import id.walt.crypto.utils.JsonUtils.toJsonObject
import id.walt.webwallet.config.ConfigManager
import id.walt.webwallet.config.OidcConfiguration
import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.OidcLogins
import id.walt.webwallet.service.OidcLoginService
import id.walt.webwallet.web.model.KeycloakAccountRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.uuid.UUID
import kotlinx.uuid.generateUUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

object KeycloakAccountStrategy : AccountStrategy<KeycloakAccountRequest>("keycloak") {
  val http =
      HttpClient() {
        install(ContentNegotiation) { json() }
        defaultRequest { header(HttpHeaders.ContentType, ContentType.Application.Json) }

        install(Logging) {
          logger = Logger.DEFAULT
          level = LogLevel.ALL
        }
      }

  val config = ConfigManager.getConfig<OidcConfiguration>()

  override suspend fun register(
      tenant: String,
      request: KeycloakAccountRequest
  ): Result<RegistrationResult> {

    val user =
        mapOf(
                "username" to request.username,
                "email" to request.email,
                "enabled" to true,
                "credentials" to listOf(mapOf("type" to "password", "value" to request.password)))
            .toJsonObject()

    val res =
        http
            .post(config.oidcUserApi) {
              contentType(ContentType.Application.Json)
              headers {
                append("Content-Type", "application/json")
                append("Authorization", "Bearer ${request.token}")
              }
              setBody(user)
            }
            .headers

    val account_id = res["Location"]!!.split("/").last()

    val createdAccountId = transaction {
      val accountId =
          Accounts.insert {
                it[Accounts.tenant] = tenant
                it[id] = UUID.generateUUID()
                it[name] = request.username
                it[email] = request.email
                it[createdOn] = Clock.System.now().toJavaInstant()
              }[Accounts.id]

      OidcLogins.insert {
        it[OidcLogins.tenant] = tenant
        it[OidcLogins.accountId] = accountId
        it[oidcId] = account_id
      }

      accountId
    }

    return Result.success(RegistrationResult(createdAccountId))
  }

  private fun verifiedToken(token: String): DecodedJWT {
    val decoded = JWT.decode(token)

    val verifier =
        JWT.require(OidcLoginService.jwkProvider.get(decoded.keyId).makeAlgorithm())
            .withIssuer(OidcLoginService.oidcRealm)
            .build()

    return verifier.verify(decoded)!!
  }

  internal fun Jwk.makeAlgorithm(): Algorithm =
      when (algorithm) {
        "RS256" -> Algorithm.RSA256(publicKey as RSAPublicKey, null)
        "RS384" -> Algorithm.RSA384(publicKey as RSAPublicKey, null)
        "RS512" -> Algorithm.RSA512(publicKey as RSAPublicKey, null)
        "ES256" -> Algorithm.ECDSA256(publicKey as ECPublicKey, null)
        "ES384" -> Algorithm.ECDSA384(publicKey as ECPublicKey, null)
        "ES512" -> Algorithm.ECDSA512(publicKey as ECPublicKey, null)
        null -> Algorithm.RSA256(publicKey as RSAPublicKey, null)
        else -> throw IllegalArgumentException("Unsupported algorithm $algorithm")
      }

  override suspend fun authenticate(
      tenant: String,
      request: KeycloakAccountRequest
  ): AuthenticatedUser {
    println("KEYCLOAK LOGIN REQUEST : $request")

    val token = getUserToken(request)

    val jwt = verifiedToken(token)

    val registeredUserId =
        if (AccountsService.hasAccountOidcId(jwt.subject)) {
          AccountsService.getAccountByOidcId(jwt.subject)!!.id
        } else {
          AccountsService.register(tenant, request).getOrThrow().id
        }
    return AuthenticatedUser(registeredUserId, jwt.subject)
  }

  suspend fun getAccessToken(): String {
    return getToken("client_credentials")
  }

  private suspend fun getUserToken(request: KeycloakAccountRequest): String {
    return getToken("password", request.username, request.password)
  }

  suspend fun getToken(
      grantType: String,
      username: String? = null,
      password: String? = null
  ): String {
    val requestParams =
        mutableMapOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "grant_type" to grantType)

    if (grantType == "password") {
      require(username != null && password != null) {
        "Username and password are required for password grant type"
      }
      requestParams["username"] = username
      requestParams["password"] = password
    }

    val requestBody = requestParams.map { (k, v) -> "$k=$v" }.joinToString("&")
    val res =
        http.post(config.accessTokenUrl) {
          headers { append("Content-Type", "application/x-www-form-urlencoded") }
          setBody(requestBody)
        }

    val resBody = Json.parseToJsonElement(res.body())
    return resBody.jsonObject["access_token"]!!.jsonPrimitive.content
  }
}

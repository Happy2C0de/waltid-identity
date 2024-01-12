package id.walt.webwallet.web.controllers

import id.walt.crypto.utils.JsonUtils.toJsonElement
import id.walt.webwallet.manifest.extractor.ManifestExtractor
import id.walt.webwallet.manifest.provider.ManifestProvider
import id.walt.webwallet.service.credentials.CredentialsService
import io.github.smiley4.ktorswaggerui.dsl.get
import io.github.smiley4.ktorswaggerui.dsl.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.json.JsonObject

fun Application.manifest() = walletRoute {
    route("manifest", {
        tags = listOf("WalletCredential manifest")
    }) {
        route("{credentialId}") {
            get({
                summary =
                    "Get credential manifest, if available, otherwise empty object"//<--TODO: decide empty response type
                request {
                    pathParameter<String>("credentialId") {
                        required = true
                        allowEmptyValue = false
                        description = "Credential id"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        body<JsonObject> {
                            description = "The display info json object"
                        }
                    }
                }
            }) {
                val manifest = getManifestByRequestParameters(call.parameters)
                context.respond(manifest.toJsonElement())
            }
            get("display", {
                summary =
                    "Get offer display info, if available, otherwise empty object"//<--TODO: decide empty response type
                request {
                    pathParameter<String>("credentialId") {
                        required = true
                        allowEmptyValue = false
                        description = "Credential id"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        body<JsonObject> {
                            description = "The display info json object"
                        }
                    }
                }
            }) {
                val manifest = getManifestByRequestParameters(call.parameters)
                context.respond(ManifestProvider.new(manifest).display())
            }
            get("issuer", {
                summary =
                    "Get offer issuer info, if available, otherwise empty object"//<--TODO: decide empty response type
                request {
                    pathParameter<String>("credentialId") {
                        required = true
                        allowEmptyValue = false
                        description = "Credential id"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        body<JsonObject> {
                            description = "The issuer info json object"
                        }
                    }
                }
            }) {
                val manifest = getManifestByRequestParameters(call.parameters)
                context.respond(ManifestProvider.new(manifest).issuer())
            }
        }
        route("extract") {
            get({
                summary =
                    "Extract manifest info from issuance request offer, if available, otherwise empty object"//<--TODO: decide empty response type
                request {
                    queryParameter<String>("offer") {
                        required = true
                        allowEmptyValue = false
                        description = "Offer request URI"
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        body<JsonObject> {
                            description = "The manifest issuer info json object"
                        }
                    }
                    HttpStatusCode.BadRequest to {
                        body<String> {
                            description = "Error message"
                        }
                    }
                }
            }) {
                call.parameters["offer"]?.let {
                    context.respond<JsonObject>(ManifestExtractor.new(it).extract(it))
                } ?: context.respond(HttpStatusCode.BadRequest, "No offer request uri provided.")
            }
        }
    }
}

internal fun getManifestByRequestParameters(parameters: Parameters): String {
    val walletId = parameters["walletId"] ?: throw IllegalArgumentException("Missing wallet id")
    val credentialId = parameters["credentialId"] ?: throw IllegalArgumentException("Missing credential id")
    return CredentialsService.Manifest.get(kotlinx.uuid.UUID(walletId), credentialId) ?: ""
}
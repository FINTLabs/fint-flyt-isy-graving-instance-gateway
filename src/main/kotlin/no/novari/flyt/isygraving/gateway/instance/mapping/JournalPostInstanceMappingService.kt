package no.novari.flyt.isygraving.gateway.instance.mapping

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.gateway.instance.InstanceMapper
import no.novari.flyt.gateway.instance.model.File
import no.novari.flyt.gateway.instance.model.instance.InstanceObject
import no.novari.flyt.isygraving.gateway.instance.model.Document
import no.novari.flyt.isygraving.gateway.instance.model.JournalPostInstance
import no.novari.flyt.isygraving.gateway.instance.model.Recipient
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class JournalPostInstanceMappingService : InstanceMapper<JournalPostInstance> {
    override fun map(
        sourceApplicationId: Long,
        incomingInstance: JournalPostInstance,
        persistFile: (File) -> UUID,
    ): InstanceObject =
        InstanceObject(
            valuePerKey =
                mapOf(
                    "tenant" to incomingInstance.tenant,
                    "id" to incomingInstance.id,
                    "caseId" to incomingInstance.caseId,
                    "caseType" to incomingInstance.caseType,
                    "businessArea" to incomingInstance.businessArea,
                    "businessAreaType" to incomingInstance.businessAreaType,
                    "caseArchiveGuid" to incomingInstance.caseArchiveGuid,
                    "municipalityName" to incomingInstance.municipalityName,
                    "locationReference" to incomingInstance.locationReference,
                    "locationReferenceFull" to incomingInstance.locationReferenceFull,
                    "locationReferenceFormatted" to incomingInstance.locationReferenceFormatted,
                    "streetName" to incomingInstance.streetName,
                    "caseDate" to incomingInstance.caseDate,
                    "caseYear" to incomingInstance.caseYear,
                    "caseResponsible" to incomingInstance.caseResponsible,
                    "status" to incomingInstance.status,
                    "statusName" to incomingInstance.statusName,
                    "archiveCaseId" to incomingInstance.archiveCaseId,
                ),
            objectCollectionPerKey =
                mutableMapOf(
                    "recipients" to incomingInstance.recipients.map(::mapRecipient),
                    "journalEntries" to
                        incomingInstance.journalEntries.map { entry ->
                            mapJournalEntry(
                                journalEntry = entry,
                                sourceApplicationId = sourceApplicationId,
                                sourceApplicationInstanceId = incomingInstance.caseId,
                                persistFile = persistFile,
                            )
                        },
                ),
        )

    private fun splitDocuments(documents: List<Document>): Pair<Document, List<Document>> {
        val mainDocument =
            documents.firstOrNull { it.mainDocument }
                ?: throw MissingMainDocumentException(
                    "Main document is required but none of the documents are flagged as mainDocument",
                )
        val attachments = documents.filterNot { it.mainDocument }
        return mainDocument to attachments
    }

    private fun mapJournalEntry(
        journalEntry: no.novari.flyt.isygraving.gateway.instance.model.JournalEntry,
        sourceApplicationId: Long,
        sourceApplicationInstanceId: String,
        persistFile: (File) -> UUID,
    ): InstanceObject {
        val (mainDocument, attachments) = splitDocuments(journalEntry.documents)
        val mainDocumentFileId =
            persistFile(
                toFile(
                    sourceApplicationId = sourceApplicationId,
                    sourceApplicationInstanceId = sourceApplicationInstanceId,
                    document = mainDocument,
                ),
            )
        log.atInfo {
            message = "Uploaded main document"
            payload =
                mapOf(
                    "sourceApplicationInstanceId" to sourceApplicationInstanceId,
                    "fileName" to mainDocument.fileName,
                    "fileId" to mainDocumentFileId,
                )
        }
        return InstanceObject(
            valuePerKey =
                mapOf(
                    "documentType" to journalEntry.documentType,
                    "mainDocumentTitle" to mainDocument.title,
                    "mainDocumentFileName" to mainDocument.fileName,
                    "mainDocumentTags" to mainDocument.tags.orEmpty().joinToString(","),
                    "mainDocumentLastModified" to mainDocument.lastModified,
                    "mainDocumentStatus" to mainDocument.status,
                    "mainDocumentMediaType" to mainDocument.mediaType,
                    "mainDocumentBase64" to mainDocumentFileId.toString(),
                ),
            objectCollectionPerKey =
                mutableMapOf(
                    "attachments" to
                        attachments.map {
                            mapAttachment(
                                document = it,
                                sourceApplicationId = sourceApplicationId,
                                sourceApplicationInstanceId = sourceApplicationInstanceId,
                                persistFile = persistFile,
                            )
                        },
                ),
        )
    }

    private fun mapRecipient(recipient: Recipient): InstanceObject =
        InstanceObject(
            valuePerKey =
                mapOf(
                    "name" to recipient.name,
                    "address" to recipient.address,
                    "postalCode" to recipient.postalCode,
                    "city" to recipient.city,
                    "organizationNumber" to recipient.organizationNumber,
                ),
        )

    private fun mapAttachment(
        document: Document,
        sourceApplicationId: Long,
        sourceApplicationInstanceId: String,
        persistFile: (File) -> UUID,
    ): InstanceObject {
        val fileId =
            persistFile(
                toFile(
                    sourceApplicationId = sourceApplicationId,
                    sourceApplicationInstanceId = sourceApplicationInstanceId,
                    document = document,
                ),
            )
        log.atInfo {
            message = "Uploaded attachment"
            payload =
                mapOf(
                    "sourceApplicationInstanceId" to sourceApplicationInstanceId,
                    "fileName" to document.fileName,
                    "fileId" to fileId,
                )
        }
        return InstanceObject(
            valuePerKey =
                mapOf(
                    "title" to document.title,
                    "fileName" to document.fileName,
                    "tags" to document.tags.orEmpty().joinToString(","),
                    "lastModified" to document.lastModified,
                    "status" to document.status,
                    "mediaType" to document.mediaType,
                    "documentBase64" to fileId.toString(),
                ),
        )
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private fun toFile(
        sourceApplicationId: Long,
        sourceApplicationInstanceId: String,
        document: Document,
    ): File {
        val mediaType = MediaType.parseMediaType(document.mediaType)
        return File(
            name = document.fileName,
            type = mediaType,
            sourceApplicationId = sourceApplicationId,
            sourceApplicationInstanceId = sourceApplicationInstanceId,
            encoding = "UTF-8",
            base64Contents = document.documentBase64,
        )
    }
}

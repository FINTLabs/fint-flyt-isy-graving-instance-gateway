package no.novari.flyt.isygraving.gateway.dispatch

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.gateway.instance.kafka.ArchiveCaseIdRequestService
import no.novari.flyt.isygraving.gateway.dispatch.DispatchContextService.Companion.INTEGRATION_CASE
import no.novari.flyt.isygraving.gateway.dispatch.DispatchContextService.Companion.INTEGRATION_JOURNALPOST
import no.novari.flyt.isygraving.gateway.dispatch.DispatchContextService.Companion.buildDispatchKey
import no.novari.flyt.isygraving.gateway.dispatch.model.DispatchReceiptEntity
import no.novari.flyt.isygraving.gateway.dispatch.repository.DispatchContextRepository
import no.novari.flyt.isygraving.gateway.dispatch.repository.DispatchReceiptRepository
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Service
@ConditionalOnProperty(
    prefix = "novari.flyt.isy-graving.dispatch",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CaseDispatchService(
    private val restClient: RestClient,
    private val dispatchContextRepository: DispatchContextRepository,
    private val dispatchReceiptRepository: DispatchReceiptRepository,
    private val archiveCaseIdRequestService: ArchiveCaseIdRequestService,
    private val objectMapper: ObjectMapper,
) {
    fun handleInstanceDispatched(instanceFlowHeaders: InstanceFlowHeaders) {
        val sourceApplicationIntegrationId = instanceFlowHeaders.sourceApplicationIntegrationId
        if (sourceApplicationIntegrationId != INTEGRATION_CASE &&
            sourceApplicationIntegrationId != INTEGRATION_JOURNALPOST
        ) {
            log.atDebug {
                message = "Skipping instance-dispatched"
                payload = mapOf("sourceApplicationIntegrationId" to sourceApplicationIntegrationId)
            }
            return
        }

        val sourceApplicationInstanceId =
            instanceFlowHeaders.sourceApplicationInstanceId
                ?: error("Missing sourceApplicationInstanceId in instance-dispatched headers")

        val dispatchKey = buildDispatchKey(sourceApplicationIntegrationId, sourceApplicationInstanceId)
        val existingReceipt = dispatchReceiptRepository.findById(dispatchKey).orElse(null)
        if (existingReceipt != null) {
            dispatchReceipt(existingReceipt)
            return
        }

        val dispatchContext =
            dispatchContextRepository.findById(dispatchKey).orElse(null)
                ?: error("Missing dispatch context for sourceApplicationInstanceId=$sourceApplicationInstanceId")

        val payload =
            when (sourceApplicationIntegrationId) {
                INTEGRATION_CASE -> {
                    buildCasePayload(
                        instanceFlowHeaders,
                        dispatchContext.caseId,
                        dispatchContext.caseArchiveGuid,
                        dispatchContext.tenant,
                    )
                }

                INTEGRATION_JOURNALPOST -> {
                    buildJournalPostPayload(
                        instanceFlowHeaders,
                        dispatchContext.caseId,
                        dispatchContext.caseArchiveGuid,
                        dispatchContext.tenant,
                    )
                }

                else -> {
                    error("Unsupported sourceApplicationIntegrationId=$sourceApplicationIntegrationId")
                }
            }

        val receipt =
            DispatchReceiptEntity(
                id = dispatchKey,
                sourceApplicationIntegrationId = sourceApplicationIntegrationId,
                sourceApplicationInstanceId = sourceApplicationInstanceId,
                callbackUrl = dispatchContext.callbackUrl,
                payload = objectMapper.writeValueAsString(payload),
            )

        dispatchReceiptRepository.save(receipt)
        dispatchContextRepository.delete(dispatchContext)
        dispatchReceipt(receipt)
    }

    @Scheduled(
        initialDelayString = "\${novari.flyt.isy-graving.dispatch.retry-initial-delay:5m}",
        fixedDelayString = "\${novari.flyt.isy-graving.dispatch.retry-fixed-delay:24h}",
    )
    fun retryFailedDispatches() {
        val pendingDispatches = dispatchReceiptRepository.findAll()
        if (pendingDispatches.isEmpty()) {
            return
        }

        log.atInfo {
            message = "Retrying dispatch receipts"
            payload = mapOf("pendingDispatches" to pendingDispatches.size)
        }
        pendingDispatches.forEach { receipt ->
            try {
                dispatchReceipt(receipt)
            } catch (ex: Exception) {
                log.atWarn {
                    message = "Retry failed"
                    payload = mapOf("dispatchReceiptId" to receipt.id)
                    cause = ex
                }
            }
        }
    }

    private fun buildCasePayload(
        instanceFlowHeaders: InstanceFlowHeaders,
        caseId: String,
        caseArchiveGuid: String,
        tenant: String,
    ): CaseDispatchPayload {
        val sourceApplicationId = instanceFlowHeaders.sourceApplicationId
        val archiveCaseId =
            instanceFlowHeaders.archiveInstanceId
                ?: archiveCaseIdRequestService.getArchiveCaseId(sourceApplicationId, caseId)
                ?: error("Missing archiveCaseId for caseId=$caseId")

        return CaseDispatchPayload(
            tenant = tenant,
            caseId = caseId,
            caseArchiveGuid = caseArchiveGuid,
            archiveCaseId = archiveCaseId,
        )
    }

    private fun buildJournalPostPayload(
        instanceFlowHeaders: InstanceFlowHeaders,
        caseId: String,
        caseArchiveGuid: String,
        tenant: String,
    ): CaseDispatchPayload {
        val archiveCaseId =
            instanceFlowHeaders.archiveInstanceId
                ?: error("Missing archiveInstanceId for journalpost caseId=$caseId")

        return CaseDispatchPayload(
            tenant = tenant,
            caseId = caseId,
            caseArchiveGuid = caseArchiveGuid,
            archiveCaseId = archiveCaseId,
        )
    }

    private fun dispatchReceipt(receipt: DispatchReceiptEntity) {
        log.atDebug {
            message = "Dispatching receipt via PUT"
            payload =
                mapOf(
                    "sourceApplicationInstanceId" to receipt.sourceApplicationInstanceId,
                    "callbackUrl" to receipt.callbackUrl,
                )
        }

        val payloadNode = objectMapper.readTree(receipt.payload)

        try {
            restClient
                .put()
                .uri(receipt.callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payloadNode)
                .retrieve()
                .toBodilessEntity()

            dispatchReceiptRepository.delete(receipt)
        } catch (ex: Exception) {
            if (isPermanentFailure(ex)) {
                log.atWarn {
                    message = "Permanent dispatch failure, deleting receipt"
                    payload =
                        mapOf(
                            "dispatchReceiptId" to receipt.id,
                            "callbackUrl" to receipt.callbackUrl,
                        )
                    cause = ex
                }
                dispatchReceiptRepository.delete(receipt)
                return
            }
            throw ex
        }
    }

    private fun isPermanentFailure(ex: Exception): Boolean =
        when (ex) {
            is IllegalArgumentException -> true
            is ResourceAccessException -> true
            is RestClientResponseException -> isPermanentHttpStatus(ex)
            else -> false
        }

    private fun isPermanentHttpStatus(ex: RestClientResponseException): Boolean {
        val status = ex.statusCode
        if (!status.is4xxClientError) {
            return false
        }
        return status.value() != 408 && status.value() != 429
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

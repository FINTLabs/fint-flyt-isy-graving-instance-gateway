package no.novari.flyt.isygraving.gateway.instance

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import no.novari.flyt.gateway.instance.InstanceProcessor
import no.novari.flyt.isygraving.gateway.dispatch.DispatchContextService
import no.novari.flyt.isygraving.gateway.instance.model.CaseInstance
import no.novari.flyt.isygraving.gateway.instance.model.CaseStatus
import no.novari.flyt.isygraving.gateway.instance.model.JournalPostInstance
import no.novari.flyt.webresourceserver.UrlPaths.EXTERNAL_API
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("$EXTERNAL_API/isygraving/instances")
class IsyGravingInstanceController(
    private val caseInstanceProcessor: InstanceProcessor<CaseInstance>,
    private val journalPostInstanceProcessor: InstanceProcessor<JournalPostInstance>,
    private val caseStatusService: CaseStatusService,
    private val dispatchContextService: DispatchContextService,
) {
    @GetMapping("{sourceApplicationInstanceId}/status")
    fun getCaseStatus(
        authentication: Authentication,
        @PathVariable sourceApplicationInstanceId: String,
    ): ResponseEntity<CaseStatus> =
        caseStatusService
            .getCaseStatus(authentication, sourceApplicationInstanceId)
            ?.let { ResponseEntity.ok(it) }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Case with sourceApplicationInstanceId=$sourceApplicationInstanceId could not be found",
            )

    @PostMapping("case")
    fun postCase(
        @Valid @RequestBody caseInstance: CaseInstance,
        authentication: Authentication,
    ): ResponseEntity<Void> =
        caseInstanceProcessor
            .processInstance(authentication, caseInstance)
            .also { dispatchContextService.save(caseInstance) }

    @PostMapping("journalpost")
    fun postJournalPost(
        @Valid @RequestBody journalPostInstance: JournalPostInstance,
        authentication: Authentication,
    ): ResponseEntity<Void> =
        journalPostInstanceProcessor
            .also {
                log.atInfo {
                    message = "Received journalpost"
                    payload =
                        mapOf(
                            "caseId" to journalPostInstance.caseId,
                            "archiveCaseId" to journalPostInstance.archiveCaseId,
                            "documents" to (
                                journalPostInstance.journalEntries
                                    .firstOrNull()
                                    ?.documents
                                    ?.size ?: 0
                            ),
                        )
                }
            }.processInstance(authentication, journalPostInstance)
            .also {
                log.atInfo {
                    message = "Journalpost processed"
                    payload = mapOf("caseId" to journalPostInstance.caseId)
                }
                dispatchContextService.save(journalPostInstance)
            }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}

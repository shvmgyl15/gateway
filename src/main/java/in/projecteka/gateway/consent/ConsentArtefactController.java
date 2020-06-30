package in.projecteka.gateway.consent;

import in.projecteka.gateway.common.Caller;
import in.projecteka.gateway.clients.HipConsentNotifyServiceClient;
import in.projecteka.gateway.clients.HiuConsentNotifyServiceClient;
import in.projecteka.gateway.common.RequestOrchestrator;
import in.projecteka.gateway.common.ResponseOrchestrator;
import in.projecteka.gateway.common.Validator;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import static in.projecteka.gateway.common.Constants.V_1_CONSENTS_HIP_NOTIFY;
import static in.projecteka.gateway.common.Constants.V_1_CONSENTS_HIU_NOTIFY;
import static in.projecteka.gateway.common.Constants.V_1_CONSENTS_HIP_ON_NOTIFY;
import static in.projecteka.gateway.common.Constants.X_HIP_ID;
import static in.projecteka.gateway.common.Constants.X_HIU_ID;
import static in.projecteka.gateway.common.Constants.X_CM_ID;
import static in.projecteka.gateway.common.Utils.requestInfoLog;
import static in.projecteka.gateway.common.Utils.responseInfoLog;


@RestController
@AllArgsConstructor
public class ConsentArtefactController {
    private static final Logger logger = LoggerFactory.getLogger(Validator.class);

    RequestOrchestrator<HipConsentNotifyServiceClient> hipConsentNotifyRequestOrchestrator;
    RequestOrchestrator<HiuConsentNotifyServiceClient> hiuConsentNotifyRequestOrchestrator;
    ResponseOrchestrator hipConsentNotifyResponseOrchestrator;

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping(V_1_CONSENTS_HIP_NOTIFY)
    public Mono<Void> consentNotifyToHIP(HttpEntity<String> requestEntity) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getClientId)
                .flatMap(clientId -> {
                    requestInfoLog(requestEntity, clientId
                            , X_CM_ID, X_HIP_ID
                            , V_1_CONSENTS_HIP_NOTIFY);
                    logger.info("Consent Artefact Flow");

                    return hipConsentNotifyRequestOrchestrator
                            .handleThis(requestEntity, X_HIP_ID, X_CM_ID, clientId);
                });
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping(V_1_CONSENTS_HIP_ON_NOTIFY)
    public Mono<Void> consentOnNotifyToHIP(HttpEntity<String> requestEntity) {
        responseInfoLog(requestEntity, "HIP" , X_CM_ID, V_1_CONSENTS_HIP_ON_NOTIFY);
        logger.info("HIP notifies about consents");

        return hipConsentNotifyResponseOrchestrator.processResponse(requestEntity, X_CM_ID);
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping(V_1_CONSENTS_HIU_NOTIFY)
    public Mono<Void> consentNotifyToHIU(HttpEntity<String> requestEntity) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getClientId)
                .flatMap(clientId -> {
                    requestInfoLog(requestEntity, clientId
                            , X_CM_ID, X_HIU_ID
                            , V_1_CONSENTS_HIU_NOTIFY);
                    logger.info("HIU notifies consents");

                    return hiuConsentNotifyRequestOrchestrator
                            .handleThis(requestEntity, X_HIU_ID, X_CM_ID, clientId);
                });
    }
}

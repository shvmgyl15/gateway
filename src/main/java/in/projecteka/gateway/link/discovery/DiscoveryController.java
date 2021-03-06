package in.projecteka.gateway.link.discovery;

import in.projecteka.gateway.clients.DiscoveryServiceClient;
import in.projecteka.gateway.common.Caller;
import in.projecteka.gateway.common.RequestOrchestrator;
import in.projecteka.gateway.common.ResponseOrchestrator;
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

import static in.projecteka.gateway.common.Constants.PATH_CARE_CONTEXTS_DISCOVER;
import static in.projecteka.gateway.common.Constants.PATH_CARE_CONTEXTS_ON_DISCOVER;
import static in.projecteka.gateway.common.Constants.API_CALLED;
import static in.projecteka.gateway.common.Constants.X_CM_ID;
import static in.projecteka.gateway.common.Constants.X_HIP_ID;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

@RestController
@AllArgsConstructor
public class DiscoveryController {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryController.class);
    RequestOrchestrator<DiscoveryServiceClient> discoveryRequestOrchestrator;
    ResponseOrchestrator discoveryResponseOrchestrator;

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping(PATH_CARE_CONTEXTS_DISCOVER)
    public Mono<Void> discoverCareContext(HttpEntity<String> requestEntity) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getClientId)
                .flatMap(clientId ->
                        discoveryRequestOrchestrator.handleThis(requestEntity, X_HIP_ID, X_CM_ID, clientId)
                                .subscriberContext(context -> context.put(API_CALLED, PATH_CARE_CONTEXTS_DISCOVER)));

    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping(PATH_CARE_CONTEXTS_ON_DISCOVER)
    public Mono<Void> onDiscoverCareContext(HttpEntity<String> requestEntity) {
        logger.debug("Request from hip: {}", keyValue("discoveryResponse", requestEntity.getBody()));
        return discoveryResponseOrchestrator.processResponse(requestEntity, X_CM_ID)
                .subscriberContext(context -> context.put(API_CALLED, PATH_CARE_CONTEXTS_ON_DISCOVER));
    }
}

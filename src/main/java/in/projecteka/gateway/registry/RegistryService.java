package in.projecteka.gateway.registry;

import in.projecteka.gateway.clients.AdminServiceClient;
import in.projecteka.gateway.clients.ClientError;
import in.projecteka.gateway.clients.FacilityRegistryClient;
import in.projecteka.gateway.clients.model.ClientResponse;
import in.projecteka.gateway.clients.model.HFRFacilityRepresentation;
import in.projecteka.gateway.clients.model.RealmRole;
import in.projecteka.gateway.common.cache.CacheAdapter;
import in.projecteka.gateway.registry.model.Bridge;
import in.projecteka.gateway.registry.model.BridgeRegistryRequest;
import in.projecteka.gateway.registry.model.BridgeServiceRequest;
import in.projecteka.gateway.registry.model.CMEntry;
import in.projecteka.gateway.registry.model.CMServiceRequest;
import in.projecteka.gateway.registry.model.FacilityRepresentation;
import in.projecteka.gateway.registry.model.HFRBridgeResponse;
import in.projecteka.gateway.registry.model.ServiceProfileResponse;
import in.projecteka.gateway.registry.model.ServiceRole;
import lombok.AllArgsConstructor;
import org.springframework.data.util.Pair;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static in.projecteka.gateway.clients.ClientError.invalidBridgeRegistryRequest;
import static in.projecteka.gateway.clients.ClientError.invalidBridgeServiceRequest;
import static in.projecteka.gateway.clients.ClientError.invalidCMEntry;
import static in.projecteka.gateway.clients.ClientError.invalidCMRegistryRequest;
import static in.projecteka.gateway.registry.ServiceType.HEALTH_LOCKER;
import static in.projecteka.gateway.registry.ServiceType.HIP;
import static in.projecteka.gateway.registry.ServiceType.HIU;

@AllArgsConstructor
public class RegistryService {
    private static final String CM_REALM_ROLE = "CM";
    private static final String FACILITY_ACTIVE = "Y";
    private final RegistryRepository registryRepository;
    private final CacheAdapter<String, String> consentManagerMappings;
    private final CacheAdapter<Pair<String, ServiceType>, String> bridgeMappings;
    private final AdminServiceClient adminServiceClient;
    private final FacilityRegistryClient facilityRegistryClient;

    public Mono<ClientResponse> populateCMEntry(CMServiceRequest request) {
        return Mono.just(request)
                .filterWhen(this::validateRequest)
                .flatMap(req -> updateCMRequest(request))
                .flatMap(updatedRequest -> registryRepository.getCMEntryIfActive(updatedRequest.getSuffix())
                        .flatMap(cmEntry -> cmEntry.isExists()
                                ? updateCMEntry(cmEntry, updatedRequest)
                                : createCMEntry(updatedRequest)
                        ));
    }

    private Mono<CMServiceRequest> updateCMRequest(CMServiceRequest request) {
        var updateCMServiceRequest = request.toBuilder();

        if (request.getIsActive() == null)
            updateCMServiceRequest.isActive(true);
        if (request.getIsBlocklisted() == null)
            updateCMServiceRequest.isBlocklisted(false);

        return Mono.just(updateCMServiceRequest.build());
    }

    private Mono<Boolean> validateRequest(CMServiceRequest request) {
        if (request != null
                && ((request.getSuffix() == null || request.getSuffix().isBlank())
                || (request.getUrl() == null || request.getUrl().isBlank())) )
            return Mono.error(invalidCMRegistryRequest());

        return Mono.just(true);
    }

    private Mono<ClientResponse> updateCMEntry(CMEntry cmEntry, CMServiceRequest request) {
        return registryRepository.updateCMEntry(request)
                .then(consentManagerMappings.invalidate(request.getSuffix()))
                .then(updateClients(cmEntry, request));
    }

    private Mono<ClientResponse> createCMEntry(CMServiceRequest request) {
        if (Boolean.TRUE.equals(request.getIsActive()))
            return registryRepository.createCMEntry(request)
                    .then(createClientAndAddRole(request.getSuffix()));
        return Mono.error(invalidCMEntry());
    }

    private Mono<ClientResponse> updateClients(CMEntry oldCMEntry, CMServiceRequest newCMEntry) {
        if (Boolean.compare(oldCMEntry.isActive(), newCMEntry.getIsActive()) == 0) {
            return Mono.empty();
        }
        if (Boolean.TRUE.equals(newCMEntry.getIsActive())) {
            return createClientAndAddRole(newCMEntry.getSuffix());
        }
        return adminServiceClient.deleteClient(newCMEntry.getSuffix()).then(Mono.empty());
    }

    private Mono<ClientResponse> createClientAndAddRole(String suffix) {
        return adminServiceClient.createClient(suffix)
                .then(addRole(suffix, CM_REALM_ROLE))
                .then(adminServiceClient.getClientSecret(suffix))
                .map(keycloakClientSecret ->
                        ClientResponse.builder()
                                .id(suffix)
                                .secret(keycloakClientSecret.getValue())
                                .build());
    }

    public Mono<ClientResponse> populateBridgeEntry(BridgeRegistryRequest bridgeRegistryRequest) {
        return registryRepository.ifPresent(bridgeRegistryRequest.getId())
                .flatMap(bridge -> bridge.getId() != null
                        ? bridgeRequest(bridge, bridgeRegistryRequest)
                        .flatMap(req -> registryRepository.updateBridgeEntry(req)
                                .then(registryRepository.fetchBridgeServicesIfPresent(req.getId()).collectList()
                                .flatMap(services -> Flux.fromIterable(services)
                                        .flatMap(service -> bridgeMappings.invalidate(Pair.of(service.getId(),
                                                service.getType()))).then()))
                                .then(req.getActive()
                                        ? createClient(bridgeRegistryRequest.getId())
                                        : adminServiceClient.deleteClientIfExists(bridgeRegistryRequest.getId())
                                        .then(Mono.empty())
                                ))
                        : bridgeRegistryRequest.getActive() == null
                        ? Mono.error(invalidBridgeRegistryRequest("Invalid request"))
                        : bridgeRegistryRequest.getActive()
                        ? registryRepository.insertBridgeEntry(bridgeRegistryRequest)
                        .then(createClient(bridgeRegistryRequest.getId()))
                        : Mono.error(invalidBridgeRegistryRequest("can't register an inactive bridge")));
    }

    private Mono<BridgeRegistryRequest> bridgeRequest(Bridge bridge, BridgeRegistryRequest request) {
        var bridgeRequest = BridgeRegistryRequest.builder()
                .id(request.getId())
                .name(request.getName() == null ? bridge.getName() : request.getName())
                .url(request.getUrl() == null ? bridge.getUrl() : request.getUrl())
                .active(request.getActive() == null ? bridge.getActive() : request.getActive())
                .blocklisted(request.getBlocklisted() == null ? bridge.getBlocklisted() : request.getBlocklisted())
                .build();
        return Mono.just(bridgeRequest);
    }

    private Mono<ClientResponse> createClient(String bridgeId) {
        return adminServiceClient.createClientIfNotExists(bridgeId)
                .then(adminServiceClient.getClientSecret(bridgeId)
                        .map(clientSecret -> ClientResponse.builder()
                                .id(bridgeId)
                                .secret(clientSecret.getValue())
                                .build()));
    }

    public Mono<Void> populateBridgeServicesEntries(String bridgeId, List<BridgeServiceRequest> bridgeServicesRequest) {
        return Flux.fromIterable(bridgeServicesRequest)
                .groupBy(BridgeServiceRequest::getId)
                .flatMap(serviceIdFlux -> serviceIdFlux
                        .collectList()
                        .flatMap(services ->
                                Flux.fromIterable(services)
                                        .flatMap(request -> request.isActive()
                                                ? registryRepository.ifPresent(request.getId(), request.getType(), request.isActive(), bridgeId)
                                                .flatMap(result -> Boolean.TRUE.equals(result)
                                                        ? Mono.error(invalidBridgeServiceRequest())
                                                        : addRole(bridgeId, request.getType().toString()))
                                                : Mono.empty())
                                        .then(upsertBridgeServiceEntries(bridgeId, services)))).then();
    }

    private Mono<Void> upsertBridgeServiceEntries(String bridgeId, List<BridgeServiceRequest> services) {
        BridgeServiceRequest serviceDetails = services.get(0);

        return Flux.fromIterable(services)
                .collectMap(BridgeServiceRequest::getType, BridgeServiceRequest::isActive)
                .flatMap(serviceTypeActiveMap ->
                        registryRepository.ifBridgeServicePresent(bridgeId, serviceDetails.getId())
                                .flatMap(result -> upsertServiceForBridge(bridgeId, serviceDetails, serviceTypeActiveMap, result))
                                .then());

    }

    private Mono<Void> upsertServiceForBridge(String bridgeId, BridgeServiceRequest serviceDetails, Map<ServiceType, Boolean> serviceTypeActiveMap, Boolean result) {
        return Boolean.TRUE.equals(result) ?
                registryRepository.updateBridgeServiceEntry(bridgeId, serviceDetails.getId(), serviceDetails.getName(), serviceTypeActiveMap)
                        .then(invalidateBridgeMappings(serviceDetails.getId(), serviceTypeActiveMap)) :
                registryRepository.insertBridgeServiceEntry(bridgeId, serviceDetails.getId(), serviceDetails.getName(), serviceTypeActiveMap);
    }

    private Mono<Void> invalidateBridgeMappings(String serviceId, Map<ServiceType, Boolean> typeActiveMap) {
        return Flux.fromIterable(typeActiveMap.keySet())
                .map(type -> bridgeMappings.invalidate(Pair.of(serviceId, type)))
                .then();
    }

    private Mono<Void> addRole(String clientId, String type) {
        return adminServiceClient.getServiceAccount(clientId)
                .flatMap(serviceAccount -> adminServiceClient.getAvailableRealmRoles(serviceAccount.getId())
                        .flatMap(realmRoles -> ifPresent(type, realmRoles)
                                .flatMap(realmRole -> adminServiceClient.assignRoleToClient(List.of(realmRole), serviceAccount.getId()))));
    }

    private Mono<RealmRole> ifPresent(String type, List<RealmRole> realmRoles) {
        return Mono.justOrEmpty(realmRoles.stream().filter(realmRole -> realmRole.getName().equalsIgnoreCase(type)).findFirst());
    }

    public Mono<ServiceProfileResponse> serviceProfile(String serviceId) {
        return registryRepository.fetchServiceEntries(serviceId)
                .flatMap(profile -> {
                    ServiceRole type;
                    var serviceProfileResponse = ServiceProfileResponse.builder()
                            .id(profile.getId())
                            .name(profile.getName())
                            .active(profile.isActive())
                            .endpoints(profile.getEndpoints());
                    if (profile.getTypes().contains(HEALTH_LOCKER)) {
                        type = ServiceRole.HEALTH_LOCKER;
                    } else if (profile.getTypes().contains(HIP) && profile.getTypes().contains(HIU)) {
                        type = ServiceRole.HIP_AND_HIU;
                    } else if (profile.getTypes().contains(HIP)) {
                        type = ServiceRole.HIP;
                    } else {
                        type = ServiceRole.HIU;
                    }
                    serviceProfileResponse.type(type);
                    return Mono.just(serviceProfileResponse.build());
                })
                .switchIfEmpty(Mono.error(ClientError.notFound("Service Id not found")));
    }

    public Mono<List<ServiceProfileResponse>> servicesOfType(String serviceType) {
        return Arrays.stream(ServiceType.values()).noneMatch(type -> type.name().equals(serviceType))
                ? Mono.just(List.of())
                : registryRepository.fetchServicesOfType(serviceType);
    }

    public Mono<HFRBridgeResponse> bridgeProfile(String bridgeId) {
        return registryRepository.bridgeProfile(bridgeId)
                .switchIfEmpty(Mono.error(ClientError.notFound("Bridge Id not found")));
    }

    public Mono<List<FacilityRepresentation>> searchFacilityByName(String name, String stateCode, String districtCode) {
        if(StringUtils.isEmpty(name)){
            return Mono.just(List.of());
        }
        return facilityRegistryClient.searchFacilityByName(name, stateCode, districtCode)
                .flatMapMany(response -> Flux.fromIterable(response.getFacilities()))
                .flatMap(this::toFacilityRepresentation)
                .collectList();
    }

    private Mono<FacilityRepresentation> toFacilityRepresentation(HFRFacilityRepresentation facility) {
        var facilityRepresentationBuilder = FacilityRepresentation.builder()
                .isHIP(false)
                .identifier(new FacilityRepresentation.Identifier(facility.getName(), facility.getId()))
                .telephone(facility.getContactNumber())
                .facilityType(List.of())
                .city(facility.getAddress().getCity());

        return registryRepository.fetchServiceEntries(facility.getId())
                .map(serviceProfile -> {
                    var isActive = facility.getActive().equals(FACILITY_ACTIVE);
                    var isHIP = serviceProfile.getTypes().contains(HIP) && isActive;
                    return facilityRepresentationBuilder
                            .isHIP(isHIP)
                            .facilityType(serviceProfile.getTypes())
                            .build();
                })
                .switchIfEmpty(Mono.just(facilityRepresentationBuilder.build()));
    }

    public Mono<FacilityRepresentation> getFacilityById(String serviceId) {
        return facilityRegistryClient.getFacilityById(serviceId)
                .flatMap(response -> {
                    if (StringUtils.isEmpty(response.getFacility().getId())) {
                        return Mono.error(ClientError.notFound("Could not find facility with given ID"));
                    }
                    return toFacilityRepresentation(response.getFacility());
                });
    }
}


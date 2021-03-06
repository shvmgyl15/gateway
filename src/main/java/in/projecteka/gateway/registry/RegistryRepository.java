package in.projecteka.gateway.registry;

import in.projecteka.gateway.common.DbOperationError;
import in.projecteka.gateway.registry.model.Bridge;
import in.projecteka.gateway.registry.model.BridgeRegistryRequest;
import in.projecteka.gateway.registry.model.BridgeService;
import in.projecteka.gateway.registry.model.CMEntry;
import in.projecteka.gateway.registry.model.CMServiceRequest;
import in.projecteka.gateway.registry.model.Endpoint;
import in.projecteka.gateway.registry.model.HFRBridgeResponse;
import in.projecteka.gateway.registry.model.ServiceProfile;
import in.projecteka.gateway.registry.model.ServiceProfileResponse;
import in.projecteka.gateway.registry.model.ServiceRole;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static in.projecteka.gateway.common.Serializer.to;


@AllArgsConstructor
public class RegistryRepository {
    private static final Logger logger = LoggerFactory.getLogger(RegistryRepository.class);

    private static final String SELECT_CM = "SELECT * FROM consent_manager where suffix = $1";
    private static final String CREATE_CM_ENTRY =
            "INSERT INTO consent_manager (name, url, cm_id, suffix, active, blocklisted, license, licensing_authority)"
                    + "VALUES ($1, $2, $3, $3, $4, $5, '', '')";
    private static final String UPDATE_CM_ENTRY =
            "UPDATE consent_manager SET name = $1, url = $2, active = $3, blocklisted = $4," +
                    " date_modified = timezone('utc'::text, now()) " +
                    "WHERE consent_manager.suffix = $5";

    private static final String SELECT_BRIDGE = "SELECT name, url, bridge_id, active, blocklisted FROM bridge " +
            "WHERE bridge_id = $1";
    private static final String INSERT_BRIDGE_ENTRY = "INSERT INTO " +
            "bridge (name, url, bridge_id, active, blocklisted) VALUES ($1, $2, $3, $4, $5)";
    private static final String UPDATE_BRIDGE_ENTRY = "UPDATE bridge SET name = $1, url = $2, active = $3, " +
            "blocklisted = $4, date_modified = timezone('utc'::text, now()) WHERE bridge.bridge_id = $5";

    private static final String SELECT_BRIDGE_SERVICE = "SELECT service_id FROM bridge_service " +
            "WHERE bridge_id = $1 AND service_id = $2";
    private static final String SELECT_BRIDGE_SERVICES = "SELECT service_id, type FROM bridge_service " +
            "WHERE bridge_id = $1 AND active = $2";
    private static final String SELECT_BRIDGE_SERVICES_BY_SERVICE_ID = "SELECT service_id, name, is_hip, is_hiu," +
            " is_health_locker, active, endpoints FROM bridge_service WHERE service_id = $1";
    private static final String SELECT_BRIDGE_PROFILE = "SELECT name, url, bridge_id, active, blocklisted, " +
            "date_created, date_modified FROM bridge WHERE bridge_id = $1";

    private final PgPool readWriteClient;
    private final PgPool readOnlyClient;

    public Mono<Bridge> ifPresent(String bridgeId) {
        return Mono.create(monoSink -> this.readOnlyClient.preparedQuery(SELECT_BRIDGE)
                .execute(Tuple.of(bridgeId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to fetch bridge"));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                monoSink.success(Bridge.builder().build());
                                return;
                            }
                            var row = iterator.next();
                            var bridge = Bridge.builder()
                                    .id(row.getString("bridge_id"))
                                    .name(row.getString("name"))
                                    .url(row.getString("url"))
                                    .active(row.getBoolean("active"))
                                    .blocklisted(row.getBoolean("blocklisted"))
                                    .build();
                            monoSink.success(bridge);
                        }));
    }

    public Mono<CMEntry> getCMEntryIfActive(String suffix) {
        return Mono.create(monoSink -> readOnlyClient.preparedQuery(SELECT_CM)
                .execute(Tuple.of(suffix), handler -> {
                    if (handler.failed()) {
                        logger.error(handler.cause().getMessage(), handler.cause());
                        monoSink.error(new DbOperationError("Failed to get the CM entry"));
                        return;
                    }
                    var iterator = handler.result().iterator();
                    if (!iterator.hasNext()) {
                        monoSink.success(CMEntry.builder().isExists(false).build());
                        return;
                    }
                    monoSink.success(cmEntryFrom(iterator.next()));
                }));
    }

    private CMEntry cmEntryFrom(Row row) {
        return CMEntry.builder()
                .isExists(true)
                .isActive(row.getBoolean("active"))
                .build();
    }

    public Mono<Void> createCMEntry(CMServiceRequest request) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(CREATE_CM_ENTRY)
                .execute(Tuple.of(request.getName(), request.getUrl(), request.getSuffix(),
                        request.getIsActive(), request.getIsBlocklisted()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to create CM entry"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> updateCMEntry(CMServiceRequest request) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(UPDATE_CM_ENTRY)
                .execute(Tuple.of(request.getName(), request.getUrl(), request.getIsActive(),
                        request.getIsBlocklisted(), request.getSuffix()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to update CM entry"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> insertBridgeEntry(BridgeRegistryRequest request) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(INSERT_BRIDGE_ENTRY)
                .execute(Tuple.of(request.getName(), request.getUrl(), request.getId(),
                        request.getActive(), request.getBlocklisted()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to insert bridge entry"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> updateBridgeEntry(BridgeRegistryRequest request) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(UPDATE_BRIDGE_ENTRY)
                .execute(Tuple.of(request.getName(), request.getUrl(),
                        request.getActive(), request.getBlocklisted(), request.getId()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to update bridge entry"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    private String prepareSelectActiveBridgeServiceQuery(String typeColumnName) {
        return "SELECT service_id FROM bridge_service WHERE service_id = $1 AND " + typeColumnName + " = $2 AND bridge_id != $3";
    }

    public Mono<Boolean> ifPresent(String serviceId, ServiceType type, boolean active, String bridgeId) {
        return select(prepareSelectActiveBridgeServiceQuery(getColumnName(type)),
                Tuple.of(serviceId, active, bridgeId),
                "Failed to fetch active bridge service");
    }

    public Mono<Boolean> ifBridgeServicePresent(String bridgeId, String serviceId) {
        return select(SELECT_BRIDGE_SERVICE,
                Tuple.of(bridgeId, serviceId),
                "Failed to fetch bridge service");
    }

    private String prepareInsertBridgeServiceQuery(Map<ServiceType, Boolean> typeActive) {
        StringBuilder typeColumnNames = new StringBuilder();
        StringBuilder typeValues = new StringBuilder();
        for(Entry<ServiceType, Boolean> entry : typeActive.entrySet()) {
            String colName = ", " + getColumnName(entry.getKey());
            String colVal = ", " + entry.getValue();
            typeColumnNames.append(colName);
            typeValues.append(colVal);
        }
        return "INSERT INTO bridge_service (bridge_id, service_id, name, active" + typeColumnNames + ") VALUES ($1, $2, $3, $4" + typeValues + ")";
    }

    public Mono<Void> insertBridgeServiceEntry(String bridgeId, String serviceId, String serviceName, Map<ServiceType, Boolean> typeActive) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(prepareInsertBridgeServiceQuery(typeActive))
                .execute(Tuple.of(bridgeId, serviceId, serviceName, true),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to insert bridge service entry"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    private String prepareUpdateBridgeServiceQuery(Map<ServiceType, Boolean> typeActive) {
        StringBuilder setTypeColumnValues = new StringBuilder();
        for(Entry<ServiceType, Boolean> entry : typeActive.entrySet()) {
            String result = getColumnName(entry.getKey()) + " = " + entry.getValue() + ", ";
            setTypeColumnValues.append(result);
        }

        return "UPDATE bridge_service SET name = $2, " + setTypeColumnValues.toString() +
                "date_modified = timezone('utc'::text, now()) FROM bridge " +
                "WHERE bridge_service.bridge_id = bridge.bridge_id AND bridge_service.bridge_id = $1 " +
                "AND bridge.active = $3 AND bridge_service.service_id = $4 AND bridge_service.active = $5";
    }

    public Mono<Void> updateBridgeServiceEntry(String bridgeId, String serviceId, String serviceName, Map<ServiceType, Boolean> typeActive) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(prepareUpdateBridgeServiceQuery(typeActive))
                .execute(Tuple.of(bridgeId, serviceName, true, serviceId, true),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to update bridge service entry"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    private Mono<Boolean> select(String query, Tuple params, String errorMessage) {
        return Mono.create(monoSink -> this.readOnlyClient.preparedQuery(query)
                .execute(params,
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError(errorMessage));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                monoSink.success(false);
                                return;
                            }
                            monoSink.success(true);
                        }));
    }

    public Flux<BridgeService> fetchBridgeServicesIfPresent(String bridgeId) {
        return Flux.create(fluxSink -> this.readOnlyClient.preparedQuery(SELECT_BRIDGE_SERVICES)
                .execute(Tuple.of(bridgeId, true),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                fluxSink.error(new DbOperationError("Failed to fetch bridge services"));
                                return;
                            }
                            RowSet<Row> results = handler.result();
                            if (results.iterator().hasNext()) {
                                results.forEach(row -> {
                                    fluxSink.next(BridgeService.builder()
                                            .id(row.getString("service_id"))
                                            .type(ServiceType.valueOf(row.getString("type")))
                                            .build());
                                });
                            }
                            fluxSink.complete();
                        }));
    }

    public Mono<ServiceProfile> fetchServiceEntries(String serviceId) {
        return Mono.create(monoSink -> this.readOnlyClient.preparedQuery(SELECT_BRIDGE_SERVICES_BY_SERVICE_ID)
                .execute(Tuple.of(serviceId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to fetch services by service id"));
                                return;
                            }
                            RowSet<Row> results = handler.result();
                            List<ServiceType> types = new ArrayList<>();
                            List<Endpoint> endpoints = new ArrayList<>();
                            final ServiceProfile.ServiceProfileBuilder[] serviceProfile = new ServiceProfile.ServiceProfileBuilder[1];
                            if (results.iterator().hasNext()) {
                                results.forEach(row -> {
                                    Object endpointJson = row.getValue("endpoints");
                                    List<Endpoint> endpointList = new ArrayList<>();
                                    if (endpointJson != null) {
                                        endpointList = to(endpointJson);
                                    }
                                    serviceProfile[0] = ServiceProfile.builder()
                                            .id(row.getString("service_id"))
                                            .name(row.getString("name"))
                                            .active(row.getBoolean("active"));
                                    var isHip = row.getBoolean("is_hip");
                                    var isHiu = row.getBoolean("is_hiu");
                                    var isHealthLocker = row.getBoolean("is_health_locker");
                                    if(Boolean.TRUE.equals(isHip)) {
                                        types.add(ServiceType.HIP);
                                    }
                                    if(Boolean.TRUE.equals(isHiu)) {
                                        types.add(ServiceType.HIU);
                                    }
                                    if(Boolean.TRUE.equals(isHealthLocker)) {
                                        types.add(ServiceType.HEALTH_LOCKER);
                                    }
                                    endpoints.addAll(endpointList);
                                });
                                serviceProfile[0].types(types);
                                serviceProfile[0].endpoints(endpoints);
                            } else {
                                monoSink.success();
                                return;
                            }
                            monoSink.success(serviceProfile[0].build());
                        }));
    }

    private String prepareSelectBridgeServicesOfTypeQuery(String typeColumnName) {
        return "SELECT service_id, name, active, endpoints FROM bridge_service WHERE " + typeColumnName + " = $1";
    }

    public Mono<List<ServiceProfileResponse>> fetchServicesOfType(String serviceType) {
        return Mono.create(monoSink -> this.readOnlyClient
                .preparedQuery(prepareSelectBridgeServicesOfTypeQuery(getColumnName(ServiceType.valueOf(serviceType))))
                .execute(Tuple.of(true),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to fetch services by type"));
                                return;
                            }
                            RowSet<Row> rowSet = handler.result();
                            List<ServiceProfileResponse> results = new ArrayList<>();
                            if (rowSet.iterator().hasNext()) {
                                rowSet.forEach(row -> {
                                    Object endpointJson = row.getValue("endpoints");
                                    results.add(ServiceProfileResponse.builder()
                                            .id(row.getString("service_id"))
                                            .name(row.getString("name"))
                                            .active(row.getBoolean("active"))
                                            .type(ServiceRole.valueOf(serviceType))
                                            .endpoints(endpointJson != null ? to(endpointJson) : Collections.emptyList())
                                            .build());
                                });
                            }
                            monoSink.success(results);
                        })
        );
    }

    private static String getColumnName(ServiceType serviceType) {
        return "is_" + serviceType.toString().toLowerCase();
    }

    public Mono<HFRBridgeResponse> bridgeProfile(String bridgeId) {
        return Mono.create(monoSink -> this.readOnlyClient.preparedQuery(SELECT_BRIDGE_PROFILE)
                .execute(Tuple.of(bridgeId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(new DbOperationError("Failed to fetch bridge profile"));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                monoSink.success();
                                return;
                            }
                            var row = iterator.next();
                            var bridgeProfile = HFRBridgeResponse.builder()
                                    .id(row.getString("bridge_id"))
                                    .name(row.getString("name"))
                                    .url(row.getString("url"))
                                    .active(row.getBoolean("active"))
                                    .blocklisted(row.getBoolean("blocklisted"))
                                    .createdAt(row.getLocalDateTime("date_created"))
                                    .modifiedAt(row.getLocalDateTime("date_modified"))
                                    .build();
                            monoSink.success(bridgeProfile);
                        }));
    }
}

package com.mapr.music.dao.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.mapr.music.annotation.MaprDbTable;
import com.mapr.music.dao.MaprDbDao;
import com.mapr.music.dao.SortOption;
import org.apache.hadoop.security.UserGroupInformation;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.ojai.Document;
import org.ojai.DocumentStream;
import org.ojai.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.Principal;
import java.util.*;

import static com.mapr.music.util.MaprProperties.MAPR_USER_GROUP;
import static com.mapr.music.util.MaprProperties.MAPR_USER_NAME;

/**
 * Implements common methods to access MapR-DB using OJAI driver.
 *
 * @param <T> model type.
 */
public abstract class MaprDbDaoImpl<T> implements MaprDbDao<T> {

    protected static final String CONNECTION_URL = "ojai:mapr:";

    protected static final Logger log = LoggerFactory.getLogger(MaprDbDaoImpl.class);

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final Class<T> documentClass;
    protected String tablePath;

    public MaprDbDaoImpl(Class<T> documentClass) {

        MaprDbTable tableAnnotation = documentClass.getAnnotation(MaprDbTable.class);
        if (tableAnnotation == null) {
            throw new IllegalArgumentException("Document class must be annotated with '" +
                    MaprDbTable.class.getCanonicalName() + " annotation.");
        }

        this.tablePath = tableAnnotation.value();
        this.documentClass = documentClass;
    }

    /**
     * {@inheritDoc}
     *
     * @return list of all documents.
     */
    @Override
    public List<T> getList() {
        return processStore((connection, store) -> {

            Stopwatch stopwatch = Stopwatch.createStarted();

            // Fetch all OJAI Documents from this store
            DocumentStream documentStream = store.find();
            List<T> documents = new ArrayList<>();
            for (Document document : documentStream) {
                T doc = mapOjaiDocument(document);
                if (doc != null) {
                    documents.add(doc);
                }
            }

            log.info("Get list of '{}' documents from '{}' table. Elapsed time: {}", documents.size(), tablePath,
                    stopwatch);

            return documents;
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param offset offset value.
     * @param limit  limit value.
     * @return list of document.
     */
    @Override
    public List<T> getList(long offset, long limit) {
        return getList(offset, limit, null, new String[]{});
    }

    /**
     * {@inheritDoc}
     *
     * @param offset      offset value.
     * @param limit       limit value.
     * @param sortOptions define the order of documents.
     * @return list of document.
     */
    @Override
    public List<T> getList(long offset, long limit, SortOption... sortOptions) {
        return getList(offset, limit, Arrays.asList(sortOptions));
    }

    /**
     * {@inheritDoc}
     *
     * @param offset offset value.
     * @param limit  limit value.
     * @param fields list of fields that will present in document.
     * @return list of document.
     */
    @Override
    public List<T> getList(long offset, long limit, String... fields) {
        return getList(offset, limit, null, fields);
    }

    /**
     * {@inheritDoc}
     *
     * @param offset      offset value.
     * @param limit       limit value.
     * @param sortOptions define the order of documents.
     * @param fields      list of fields that will present in document.
     * @return list of document.
     */
    @Override
    public List<T> getList(long offset, long limit, List<SortOption> sortOptions, String... fields) {
        return processStore((connection, store) -> {

            Stopwatch stopwatch = Stopwatch.createStarted();

            Query query = buildQuery(connection, offset, limit, fields, sortOptions);

            // Fetch all OJAI Documents from this store according to the built query
            DocumentStream documentStream = store.findQuery(query);
            List<T> documents = new ArrayList<>();
            for (Document document : documentStream) {
                T doc = mapOjaiDocument(document);
                if (doc != null) {
                    documents.add(doc);
                }
            }

            log.info("Get list of '{}' documents from '{}' table with offset: '{}', limit: '{}', sortOptions: '{}', " +
                            "fields: '{}'. Elapsed time: {}", documents.size(), tablePath, offset, limit, sortOptions,
                    (fields != null) ? Arrays.asList(fields) : "[]", stopwatch);

            return documents;
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param id document's identifier.
     * @return document with the specified identifier.
     */
    @Override
    public T getById(String id) {
        return getById(id, new String[]{});
    }

    /**
     * {@inheritDoc}
     *
     * @param id     document's identifier.
     * @param fields list of fields that will present in document.
     * @return document with the specified identifier.
     */
    @Override
    public T getById(String id, String... fields) {
        return processStore((connection, store) -> {

            Stopwatch stopwatch = Stopwatch.createStarted();

            // Fetch single OJAI Document from store by it's identifier. Use projection if fields are defined.
            Document ojaiDoc = (fields == null || fields.length == 0) ? store.findById(id) : store.findById(id, fields);

            log.info("Get by ID from '{}' table with id: '{}', fields: '{}'. Elapsed time: {}", tablePath, id,
                    (fields != null) ? Arrays.asList(fields) : "[]", stopwatch);

            return (ojaiDoc == null) ? null : mapOjaiDocument(ojaiDoc);
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param storeAction specifies action which will be performed on store.
     * @param <R>         type of {@link OjaiStoreAction} return value.
     * @return process result.
     */
    @Override
    public <R> R processStore(OjaiStoreAction<R> storeAction) {

        loginTestUser(MAPR_USER_NAME, MAPR_USER_GROUP);

        // Create an OJAI connection to MapR cluster
        Connection connection = DriverManager.getConnection(CONNECTION_URL);

        // Get an instance of OJAI DocumentStore
        final DocumentStore store = connection.getStore(tablePath);

        R processingResult = null;
        if (storeAction != null) {
            processingResult = storeAction.process(connection, store);
        }

        // Close this instance of OJAI DocumentStore
        store.close();

        // Close the OJAI connection and release any resources held by the connection
        connection.close();

        return processingResult;
    }

    /**
     * {@inheritDoc}
     *
     * @param storeVoidAction specifies action which will be performed on store.
     */
    @Override
    public void processStore(OjaiStoreVoidAction storeVoidAction) {

        OjaiStoreAction<Optional> storeAction = (connection, store) -> {
            storeVoidAction.process(connection, store);
            return Optional.empty();
        };

        processStore(storeAction);
    }

    /**
     * {@inheritDoc}
     *
     * @param id identifier of document which will be deleted.
     */
    @Override
    public void deleteById(String id) {
        processStore((connection, store) -> {
            Stopwatch stopwatch = Stopwatch.createStarted();
            store.delete(id);
            log.info("Delete by ID from '{}' table with id: '{}'. Elapsed time: {}", tablePath, id, stopwatch);
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param entity contains info for document, which will be created.
     * @return created document.
     */
    @Override
    public T create(T entity) {
        return processStore((connection, store) -> {

            Stopwatch stopwatch = Stopwatch.createStarted();

            // Create an OJAI Document form the Java bean (there are other ways too)
            final Document createdOjaiDoc = connection.newDocument(entity);

            // Set update info if available
            getUpdateInfo().ifPresent(updateInfo -> createdOjaiDoc.set("update_info", updateInfo));

            // Insert the document into the OJAI store
            store.insertOrReplace(createdOjaiDoc);

            log.info("Create document '{}' at table: '{}'. Elapsed time: {}", createdOjaiDoc, tablePath, stopwatch);

            // Map Ojai document to the actual instance of model class
            return mapOjaiDocument(createdOjaiDoc);
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param id     identifier of document, which will be updated.
     * @param entity contains info for document, which will be updated.
     * @return updated document.
     */
    @Override
    public abstract T update(String id, T entity);

    /**
     * {@inheritDoc}
     *
     * @param id document's identifier.
     * @return <code>true</code> if document with specified identifier exists, <code>false</code> otherwise.
     */
    @Override
    public boolean exists(String id) {
        return processStore((connection, store) -> store.findById(id) != null);
    }

    /**
     * {@inheritDoc}
     *
     * @param ojaiDocument OJAI document which will be converted.
     * @return instance of the model class.
     */
    @Override
    public T mapOjaiDocument(Document ojaiDocument) {

        if (ojaiDocument == null) {
            throw new IllegalArgumentException("OJAI document can not be null");
        }

        T document = null;
        try {
            document = mapper.readValue(ojaiDocument.toString(), documentClass);
        } catch (IOException e) {
            log.warn("Can not map OJAI document '{}' to instance of '{}' class. Exception: {}", ojaiDocument,
                    documentClass.getCanonicalName(), e);
        }

        return document;
    }

    /**
     * Constructs and returns map, which contains document update information.
     *
     * @return map, which contains document update information.
     */
    protected Optional<Map<String, Object>> getUpdateInfo() {

        Principal principal = ResteasyProviderFactory.getContextData(Principal.class);
        if (principal == null) {
            return Optional.empty();
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("user_id", principal.getName());
        userInfo.put("date_of_operation", System.currentTimeMillis());

        return Optional.of(userInfo);
    }

    /**
     * Build an OJAI query according to the specified offset and limit values. Given fields array will be used in
     * projection.
     *
     * @param connection OJAI connection.
     * @param offset     offset value.
     * @param limit      limit value.
     * @param fields     fields what will present in returned document. Used for projection.
     * @return OJAI query, which is built according to the specified parameters.
     */
    private Query buildQuery(Connection connection, long offset, long limit, String[] fields, List<SortOption> options) {

        Query query = connection.newQuery();
        if (fields == null || fields.length == 0) {
            query.select("*");
        } else {
            query.select(fields);
        }

        if (options == null || options.isEmpty()) {
            return query.offset(offset).limit(limit).build();
        }

        for (SortOption sortOption : options) {
            SortOrder ojaiSortOrder = (SortOption.Order.DESC == sortOption.getOrder()) ? SortOrder.DESC : SortOrder.ASC;
            for (String field : sortOption.getFields()) {
                query = query.orderBy(field, ojaiSortOrder);
            }
        }

        return query.offset(offset).limit(limit).build();
    }

    private static void loginTestUser(String username, String group) {
        UserGroupInformation currentUgi = UserGroupInformation.createUserForTesting(username, new String[]{group});
        UserGroupInformation.setLoginUser(currentUgi);
    }
}
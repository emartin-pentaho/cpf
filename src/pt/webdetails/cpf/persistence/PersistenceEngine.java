/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
package pt.webdetails.cpf.persistence;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentPool;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import java.text.SimpleDateFormat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


import com.orientechnologies.orient.server.OServerMain;
import com.orientechnologies.orient.server.OServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pentaho.platform.api.engine.IParameterProvider;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.reporting.libraries.base.util.StringUtils;

import pt.webdetails.cpf.InvalidOperationException;
import pt.webdetails.cpf.CpfProperties;
import pt.webdetails.cpf.Util;
import pt.webdetails.cpf.repository.RepositoryAccess;

/**
 *
 * @author pdpi
 */
public class PersistenceEngine {

    private static final Log logger = LogFactory.getLog(PersistenceEngine.class);
    private static PersistenceEngine _instance;
    private static final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final CpfProperties SETTINGS = CpfProperties.getInstance();
    private static final String DBURL = SETTINGS.getProperty("ORIENT.DBURL");
    private static final String DBUSERNAME = SETTINGS.getProperty("ORIENT.USER");
    private static final String DBPASSWORD = SETTINGS.getProperty("ORIENT.PASSWORD");
    private static final int JSON_INDENT = 2;

    public static synchronized PersistenceEngine getInstance() {
        if (_instance == null) {
            _instance = new PersistenceEngine();
        }
        return _instance;
    }

    private enum Method {

        HEAD, DELETE, GET, STORE, QUERY
    }

    private PersistenceEngine() {
        try {
            logger.info("Creating PersistenceEngine instance");
            initialize();
        } catch (Exception ex) {
            logger.fatal("Could not create PersistenceEngine: " + Util.getExceptionDescription(ex)); //$NON-NLS-1$
            return;
        }

    }

    private String getOrientPath() {
        return PentahoSystem.getApplicationContext().getSolutionPath("system/.orient");
    }

    private void initialize() throws Exception {

        //Ensure .orient folder exists on system
        String orientPath = getOrientPath();
        File dirPath = new File(orientPath);
        if (!dirPath.exists()) {
            dirPath.mkdir();
        }
        startOrient();
    }

    public String process(IParameterProvider requestParams, IPentahoSession userSession) throws InvalidOperationException {
        String methodString = requestParams.getStringParameter("method", "none");
        JSONObject reply = null;
        try {
            Method mthd = Method.valueOf(methodString.toUpperCase());
            switch (mthd) {
                case DELETE:
                    reply = deleteRecord(requestParams, userSession);
                    break;
                case GET:
                    reply = get(requestParams, userSession);
                    break;
                case STORE:
                    reply = store(requestParams, userSession);
                    break;
                case QUERY:
                    if (!SecurityHelper.isPentahoAdministrator(PentahoSessionHolder.getSession())) {
                        throw new SecurityException("Arbitrary querying is only available to admins");
                    }
                    reply = query(requestParams, userSession);
                    break;
            }

            return reply.toString(JSON_INDENT);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid method: " + methodString);
            return "{'result': false}";
        } catch (JSONException e) {
            logger.error("error processing: " + methodString);
            return "{'result': false}";
        }
    }

    //TODO: changed temporarily from private
    public Object executeCommand(String query, Map<String, Object> params) {
        ODatabaseDocumentTx db = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);
        try {
            OCommandSQL preparedQuery = new OCommandSQL(query);
            if (params == null) {
                return db.command(preparedQuery).execute();
            } else {
                return db.command(preparedQuery).execute(params);
            }
        } catch (Exception e) {
            logger.error(e);
            return null;
        } finally {
            if (db != null) {
                db.close();
            }
        }

    }

    //TODO: changed temporarily from private
    public List<ODocument> executeQuery(String query, Map<String, Object> params) {
        ODatabaseDocumentTx db = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);
        try {
            OSQLSynchQuery<ODocument> preparedQuery = new OSQLSynchQuery<ODocument>(query);
            if (params == null) {
                return db.command(preparedQuery).execute();
            } else {
                return db.command(preparedQuery).execute(params);
            }
        } catch (Exception e) {
            logger.error(e);
            return null;
        } finally {
            if (db != null) {
                db.close();
            }
        }

    }

    //TODO: delete inner stuff
    public synchronized int deleteAll(String classTable) {
        ODatabaseDocumentTx db = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);
        int counter = 0;
        try {
            counter = 0;
            for (ODocument doc : db.browseClass(classTable)) {

//        for(Object inner: doc.fieldValues()){//nah...
//          if(inner instanceof ODocument){
//            ((ODocument)inner).delete();
//          }
//        }

                doc.delete();
                counter++;
            }
        } catch (Exception e) {
            logger.error(e);
            return counter;
        } finally {
            if (db != null) {
                db.close();
            }
        }
        return counter;
    }

    //TODO:adapt
    public ODocument createDocument(String baseClass, String json) {
        ODocument doc = new ODocument(baseClass);
        doc.fromJSON(json);
        return doc;
    }

    public ODocument createDocument(String baseClass, JSONObject json) {
        ODocument doc = new ODocument(baseClass);
        @SuppressWarnings("unchecked")
        Iterator<String> fields = json.keys();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.equals("key")) {
                continue;
            }

            try {
                Object value = json.get(field);
                if (value instanceof JSONObject) {

                    doc.field(field, createDocument(baseClass + "_" + field, (JSONObject) value));

                    JSONObject obj = json.getJSONObject(field);
                    logger.debug("obj:" + obj.toString(2));
                } else {
                    doc.field(field, value);
                }

            } catch (JSONException e) {
                logger.error(e);
            }
        }

        return doc;
    }

    //TODO: adapt
    private JSONObject createJson(ODocument doc) {
        JSONObject json = new JSONObject();

        for (String field : doc.fieldNames()) {
            try {
                Object value = doc.field(field); //doc.<Object>field(field)
                if (value instanceof ODocument) {
                    ODocument docVal = (ODocument) value;
                    logger.debug("obj odoc:" + docVal.toJSON());
                    json.put(field, createJson(docVal));
                } else if (value != null) {
                    logger.debug(value.getClass());
                    json.put(field, value);
                }
            } catch (JSONException e) {
                logger.error(e);
            }
        }

        return json;
    }

    private JSONObject query(IParameterProvider requestParams, IPentahoSession userSession) throws JSONException {
        final String queryString = requestParams.getStringParameter("query", "");
        return query(queryString, (Map) null);
    }

    public JSONObject query(String query, Map<String, Object> params) throws JSONException {
        JSONObject json = new JSONObject();

        try {
            json.put("result", Boolean.TRUE);

            List<ODocument> result = executeQuery(query, params);
            if (result != null) {
                JSONArray arr = new JSONArray();
                for (ODocument resDoc : result) {
                    arr.put(new JSONObject(resDoc.toJSON()));
                }
                json.put("object", arr);
            }
        } catch (ODatabaseException ode) {
            json.put("result", Boolean.FALSE);
            json.put("errorMessage", "DatabaseException: Review query");
            logger.error(getExceptionDescription(ode));

        }
        return json;
    }

    public JSONObject command(String query, Map<String, Object> params) throws JSONException {
        JSONObject json = new JSONObject();

        try {


            Object result = executeCommand(query, params);
            if (result != null) {
                json.put("result", Boolean.TRUE);
            } else {
            }
        } catch (ODatabaseException ode) {
            json.put("result", Boolean.FALSE);
            json.put("errorMessage", "DatabaseException: Review query");
            logger.error(getExceptionDescription(ode));

        }
        return json;
    }

    private JSONObject get(IParameterProvider requestParams, IPentahoSession userSession) throws JSONException {
        final String id = requestParams.getStringParameter("rid", "");
        return get(id);
    }

    public JSONObject get(String id) throws JSONException {
        JSONObject json = new JSONObject(), resultJson;


        try {
            json.put("result", Boolean.TRUE);
            String user = PentahoSessionHolder.getSession().getName();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", id);
            params.put("user", user);
            List<ODocument> result = executeQuery("select * from Query where @rid = :id and userid = :user", params);

            ODocument doc;

            if (result.size() == 1) {
                json.put("object", new JSONObject(result.get(0).toJSON()));
            } else {
                json.put("result", Boolean.FALSE);
                json.put("errorMessage", "Multiple elements found with id " + id);
            }
        } catch (ODatabaseException orne) {

            if (orne.getCause().getClass() == ORecordNotFoundException.class) {
                logger.error("Record with id " + id + " not found");
                json.put("result", Boolean.FALSE);
                json.put("errorMessage", "No record found with id " + id);
            } else {
                logger.error(getExceptionDescription(orne));
                throw orne;
            }
        }

        return json;
    }

    private JSONObject deleteRecord(IParameterProvider requestParams, IPentahoSession userSession) throws JSONException {
        final String id = requestParams.getStringParameter("rid", "");
        return deleteRecord(id);
    }

    public JSONObject deleteRecord(String id) throws JSONException {


        JSONObject json = new JSONObject();
        String user = PentahoSessionHolder.getSession().getName();
        ODatabaseDocumentTx db = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);
        try {

            ODocument doc = db.getRecord(new com.orientechnologies.orient.core.id.ORecordId(id));

            if (doc == null) {
                json.put("result", Boolean.FALSE);
                json.put("errorMessage", "No element found with id " + id);
                return json;
            }
            doc.delete();
            json.put("result", Boolean.TRUE);
        } catch (ODatabaseException orne) {

            if (orne.getCause().getClass() == ORecordNotFoundException.class) {
                logger.error("Record with id " + id + " not found");
                json.put("result", Boolean.FALSE);
                json.put("errorMessage", "No record found with id " + id);
            } else {
                logger.error(getExceptionDescription(orne));
                throw orne;
            }
        } finally {
            if (db != null) {
                db.close();
            }
        }
        return json;
    }

    private JSONObject store(IParameterProvider requestParams, IPentahoSession userSession) throws JSONException {
        final String id = requestParams.getStringParameter("rid", "");
        String className = requestParams.getStringParameter("class", "");
        String data = requestParams.getStringParameter("data", "");
        return store(id, className, data);
    }

    public boolean initializeClass(String className) {
        ODatabaseDocumentTx database = null;
        try {
            database = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);
            if (database.getMetadata().getSchema().getClass(className) != null) {
                return false;
            } else {
                database.getMetadata().getSchema().createClass(className);
                return true;
            }
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    public JSONObject store(Persistable obj) {
        String key = obj.getKey();
        String className = obj.getClass().getName();
        try {
            JSONObject json = obj.toJSON();
            JSONObject ret = store(key, className, json);
            obj.setKey(ret.getString("id"));
            return ret;
        } catch (JSONException e) {
            return null;
        }
    }

    public JSONObject store(String id, String className, JSONObject data) {
        return store(id, className, data, null);
    }

    public synchronized JSONObject store(String id, String className, JSONObject data, ODocument doc) {
        JSONObject json = new JSONObject();
        try {
            ODatabaseDocumentTx db = null;
            String user = PentahoSessionHolder.getSession().getName();
            try {

                json.put("result", Boolean.TRUE);


                db = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);


                if (!StringUtils.isEmpty(id)) {

                    Map<String, Object> params = new HashMap<String, Object>();
                    params.put("id", id);
                    List<ODocument> result = executeQuery("select * from " + className +" where @rid = :id", params);
                    if (result.size() == 1) {
                        doc = result.get(0);
                        if (!doc.field("userid").toString().equals(user)) {
                            json.put("result", Boolean.FALSE);
                            json.put("errorMessage", "Object id " + id + " belongs to another user");
                            return json;
                        }

                        fillDocument(doc, data);

                    } else if (result.size() == 0) {
                        json.put("result", Boolean.FALSE);
                        json.put("errorMessage", "No " + className + " found with id " + id);
                        return json;
                    } else {
                        json.put("result", Boolean.FALSE);
                        json.put("errorMessage", "Multiple " + className + " found with id " + id);
                        return json;
                    }
                } else if (doc == null) {
                    doc = createDocument(id, className, data, json, db, user);
                }
                // SAVE THE DOCUMENT
                doc.save();

                if (id == null || id.length() == 0) {
                    ORID newId = doc.getIdentity();
                    json.put("id", newId.toString());
                }

                return json;
            } catch (ODatabaseException orne) {

                if (orne.getCause().getClass() == ORecordNotFoundException.class) {
                    logger.error("Record with id " + id + " not found");
                    json.put("result", Boolean.FALSE);
                    json.put("errorMessage", "No " + className + " found with id " + id);
                    return json;
                }

                logger.error(getExceptionDescription(orne));
                throw orne;
            } finally {
                if (db != null) {
                    db.close();
                }
            }
        } catch (JSONException e) {
            return json;
        }
    }

    /**
     * generic json to document
     */
    private ODocument createDocument(String id, String className, JSONObject data, JSONObject json, ODatabaseDocumentTx db, String user) throws JSONException {
        ODocument doc = new ODocument(db, className);
        doc.field("userid", user);
        fillDocument(doc, data);
        return doc;
    }

    private void fillDocument(ODocument doc, JSONObject data) throws JSONException {
        doc.fromJSON(data.toString(2));
        /*
        Iterator keyIterator = data.keys();
        while (keyIterator.hasNext()) {
            String key = (String) keyIterator.next();
            doc.field(key, data.getString(key));
        }*/
    }

    public JSONObject store(String id, String className, String inputData) throws JSONException {
        JSONObject data = new JSONObject(inputData);
        return store(id, className, data);
    }

    private String getExceptionDescription(Exception ex) {
        return ex.getCause().getClass().getName() + " - " + ex.getMessage();
    }

    public void startOrient() throws Exception {
        InputStream conf;
        try {
            conf = RepositoryAccess.getRepository().getResourceInputStream("/cpf/orient.xml");
        } catch (Exception e) {
            logger.warn("Falling back to built-in config");
            conf = getClass().getResourceAsStream("orient.xml");
        }

        /* Acquiring a database connection will throw an
         * exception if the db isn't up. We take advantage
         * of that here, to decide whether we need to start
         * up the server
         */

        ODatabaseDocumentTx tx = null;
        try {
            tx = ODatabaseDocumentPool.global().acquire(DBURL, DBUSERNAME, DBPASSWORD);
        } catch (Exception e) {

            //Change the default database location to orientPath
            String confAsString = IOUtils.toString(conf, "UTF-8");
            confAsString = confAsString.replaceAll(Matcher.quoteReplacement("$PATH$"), getOrientPath() + "/");
            conf.close();
            conf = new ByteArrayInputStream(confAsString.getBytes("UTF-8"));

            OServer server = OServerMain.create();
            server.startup(conf);
            server.activate();

        } finally {
            if (tx != null) {
                tx.close();
            }
            conf.close();
        }
    }
}

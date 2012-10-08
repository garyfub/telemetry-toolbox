/*
 * Copyright 2012 Mozilla Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mozilla.telemetry.pig.eval.json;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.ParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Iterator;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;


import org.apache.pig.EvalFunc;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.util.UDFContext;

//import org.apache.pig.tools.counters.PigCounterHelper;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.commons.lang.StringUtils;

import org.apache.log4j.Logger;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import com.twitter.elephantbird.pig.util.PigCounterHelper;

import com.mozilla.telemetry.constants.TelemetryConstants;

public class TelemetryInvalidCounts extends EvalFunc<Tuple> {
    static Map <String, Object> specValues = null;
    final String lookupFileName;
    static final Logger LOG = Logger.getLogger(TelemetryInvalidCounts.class);
    PigCounterHelper pigCounterHelper = new PigCounterHelper();
    TupleFactory tupleFactory = TupleFactory.getInstance();
    enum ReportStats {VALID_HISTOGRAM,INVALID_HISTOGRAM, INVALID_JSON_STRUCTURE,INVALID_SUBMISSIONS,KNOWN_HISTOGRAMS,
                             UNKNOWN_HISTOGRAMS, META_DATA_INVALID, UNDEFINED_HISTOGRAMS, MISSING_JSON_REFERENCE,
                             SUBMISSIONS_EVALUATED, SUBMISSIONS_SKIPPED, MISSING_JSON_VALUES_FIELD,JSON_INVALID_VALUES_FIELD,
                             INVALID_HISTOGRAM_BUCKET_VALUE,INVALID_HISTOGRAM_BUCKET_COUNT,INVALID_HISTOGRAM_MAX,
                             INVALID_HISTOGRAM_MIN,INVALID_HISTOGRAM_TYPE,NO_HISTOGRAM_BUCKET_VALUES};
    
    public TelemetryInvalidCounts(String filename) {
        lookupFileName = filename;
    }

    @Override
    public List<String> getCacheFiles() {
        List<String> cacheFiles = new ArrayList<String>(1);
        cacheFiles.add(lookupFileName + "#" + lookupFileName);
        return cacheFiles;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Tuple exec(Tuple input) throws IOException {
        if(specValues == null) {
            readLookupFile(lookupFileName);
        }
        String key = (String)input.get(0);
        String json = (String)input.get(1);
        if(json == null) {
            pigCounterHelper.incrCounter(ReportStats.META_DATA_INVALID,1L);
        } else {
            String newJson = validateTelemetryJson(json);
            if(newJson != null) {
                Tuple output = tupleFactory.newTuple(2);
                output.set(0,key);
                output.set(1,newJson);
                return output;
            } 
        }
        return null;
    }

    public void readLookupFile(String filename) {
        try {
            FileSystem fs = FileSystem.get(UDFContext.getUDFContext().getJobConf());
            FSDataInputStream fi = fs.open(new Path(filename));
            BufferedReader in = new BufferedReader(new InputStreamReader(fi));
            String line;
            specValues = new HashMap<String,Object>();
            while ((line = in.readLine()) != null) {
                String[] toks = new String[2];
                toks = line.split(":", 2);
                if(toks.length == 2) {
                    Map<String, Map<String,Object>> referenceJson = readReferenceJson(toks[1]);
                    specValues.put(toks[0],referenceJson);
                }
            }
            in.close();
        } catch(Exception e) {
        }
    }
    
    @SuppressWarnings("unchecked")
    public Map<String, Map<String,Object>>  readReferenceJson(String filename) {
        try {
            FileSystem fs = FileSystem.get(UDFContext.getUDFContext().getJobConf());
            Map<String, Map<String,Object>> referenceValues = new HashMap<String, Map<String,Object>>();
            ObjectMapper jsonMapper = new ObjectMapper();
            Map<String, Object> crash = new LinkedHashMap<String, Object>();
            crash = jsonMapper.readValue(fs.open(new Path(filename)), new TypeReference<Map<String,Object>>() { });
            LinkedHashMap<String, Object> dumpValue = (LinkedHashMap<String, Object>) crash.get(TelemetryConstants.HISTOGRAMS);

            for(Map.Entry<String, Object> entry : dumpValue.entrySet()) {
                Map<String, Object> compKey = new HashMap<String, Object>();
                String jKey = entry.getKey();
                compKey.put(TelemetryConstants.NAME, jKey);
                LinkedHashMap<String, Object> d1 = (LinkedHashMap<String, Object>) entry.getValue();
                List <Integer> buckets = new ArrayList<Integer>();
                for(Map.Entry<String, Object> e1 : d1.entrySet()) {

                    if (StringUtils.equals(e1.getKey(), TelemetryConstants.MIN)) {
                        compKey.put(TelemetryConstants.MIN, e1.getValue() + "");
                    }

                    if (StringUtils.equals(e1.getKey(), TelemetryConstants.MAX)) {
                        compKey.put(TelemetryConstants.MAX, e1.getValue() + "");
                    }

                    if (StringUtils.equals(e1.getKey(), TelemetryConstants.KIND)) {
                        compKey.put(TelemetryConstants.HISTOGRAM_TYPE, e1.getValue() + "");	
                    }
                    
                    if (StringUtils.equals(e1.getKey(), TelemetryConstants.BUCKET_COUNT)) {
                        compKey.put(TelemetryConstants.BUCKET_COUNT, e1.getValue() + "");
                    }

                    if (StringUtils.equals(e1.getKey(), TelemetryConstants.BUCKETS)) {
                        buckets = (List<Integer>) e1.getValue();
                        compKey.put(TelemetryConstants.BUCKETS , buckets);
                    }

                }
                referenceValues.put(jKey, compKey);
            }
            return referenceValues;

        } catch (IOException e) {
            LOG.info(e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public String getAppVersionFromTelemetryDoc(Map<String,Object> crash) {
        String appVersion = null;
        LinkedHashMap<String, Object> infoValue = (LinkedHashMap<String, Object>) crash.get(TelemetryConstants.INFO);
        for(Map.Entry<String, Object> e1 : infoValue.entrySet()) {
            if (e1.getKey().equals(TelemetryConstants.APP_VERSION)) 
                appVersion = e1.getValue().toString();
        }
        return appVersion;
    }
    
    @SuppressWarnings("unchecked")
    public boolean checkVersion(String appVersion) {
        boolean appVersionMatch = false;
        for(Map.Entry<String, Object> entry : specValues.entrySet()) {
            if (appVersion.contains(entry.getKey()))
                appVersionMatch = true;
        }
        return  appVersionMatch;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String,Object>> getJsonSpec(String appVersion) {
        for(Map.Entry<String, Object> entry : specValues.entrySet()) {
            if (appVersion.contains(entry.getKey())) {
                return (Map<String, Map<String,Object>>) specValues.get(entry.getKey());
            }
        }
        return null;
    }
    
    @SuppressWarnings("unchecked")
    public String validateTelemetryJson(String json) {
        String jsonValue = new String(json);
        ObjectMapper jsonMapper = new ObjectMapper();
        Map<String, Object> crash = new LinkedHashMap<String, Object>();
        Map<String, Object> newJson = new LinkedHashMap<String, Object>();
        Map<String, Object> finalJson = new LinkedHashMap<String, Object>();
        try {
            crash = jsonMapper.readValue(jsonValue, new TypeReference<Map<String,Object>>() { });
            String appVersion = getAppVersionFromTelemetryDoc(crash);
            boolean appVersionMatch = checkVersion(appVersion);
            finalJson.put(TelemetryConstants.VER, crash.get(TelemetryConstants.VER));
            finalJson.put(TelemetryConstants.INFO, crash.get(TelemetryConstants.INFO));
            if (appVersionMatch) {
                Map<String, Map<String,Object>> referenceValues = getJsonSpec(appVersion);
                pigCounterHelper.incrCounter(ReportStats.SUBMISSIONS_EVALUATED,1L);
                finalJson.put(TelemetryConstants.SIMPLE_MESAUREMENTS, crash.get(TelemetryConstants.SIMPLE_MESAUREMENTS));
                Map<String, Object> intermediateJson = new LinkedHashMap<String, Object>();
                Map<String, Object> missingJson = new LinkedHashMap<String, Object>();
                LinkedHashMap<String, Object> dumpValue = (LinkedHashMap<String, Object>) crash.get(TelemetryConstants.HISTOGRAMS);
                
                for(Map.Entry<String, Object> entry : dumpValue.entrySet()) {
                    String jKey = entry.getKey();
                    String min = new String();
                    String max = new String();
                    String histogram_type = new String();
                    String bucket_count = new String();
                    boolean validHistogram = true;
                    Map<String,Object> bucket_values = new LinkedHashMap<String,Object>();
                    newJson = new LinkedHashMap<String, Object>();
                    LinkedHashMap<String, Object> d1 = (LinkedHashMap<String, Object>) entry.getValue();
                    
                    for(Map.Entry<String, Object> e1 : d1.entrySet()) {
                        if (StringUtils.equals(e1.getKey(), TelemetryConstants.RANGE)) {
                            List<Integer> d2 = (List<Integer>) e1.getValue();
                            min = d2.get(0)+"";
                            max = d2.get(1)+"";
                        } else if (StringUtils.equals(e1.getKey(), TelemetryConstants.HISTOGRAM_TYPE)) {
                            histogram_type = e1.getValue() + "";
                        } else if (StringUtils.equals(e1.getKey(), TelemetryConstants.BUCKET_COUNT)) {
                            bucket_count = e1.getValue() + "";
                        } else if(StringUtils.equals(e1.getKey(), TelemetryConstants.VALUES)) {
                            bucket_values = (LinkedHashMap<String,Object>) e1.getValue();
                        }
                    }
                        
                    
                    if (referenceValues.containsKey(jKey)) {
                        pigCounterHelper.incrCounter(ReportStats.KNOWN_HISTOGRAMS,1L);
                        Map<String,Object> referenceHistograms = referenceValues.get(jKey);
                        String reference_histogram_type = (String)referenceHistograms.get(TelemetryConstants.HISTOGRAM_TYPE);

                        if (!StringUtils.equals(reference_histogram_type,histogram_type)) {
                            validHistogram = false;
                            //LOG.info("Name "+jKey+"Kind "+histogram_type+"reference Kind "+reference_histogram_type);
                            pigCounterHelper.incrCounter(ReportStats.INVALID_HISTOGRAM_TYPE,1L);
                        }
                        if (!StringUtils.equals((String)referenceHistograms.get(TelemetryConstants.MIN),min)) {
                            validHistogram = false;
                            pigCounterHelper.incrCounter(ReportStats.INVALID_HISTOGRAM_MIN,1L);
                        }
                        if (!StringUtils.equals((String)referenceHistograms.get(TelemetryConstants.MAX), max)) {
                            validHistogram = false;
                            pigCounterHelper.incrCounter(ReportStats.INVALID_HISTOGRAM_MAX,1L);
                        }
                        if (!StringUtils.equals((String)referenceHistograms.get(TelemetryConstants.BUCKET_COUNT),bucket_count)) {
                            validHistogram = false;
                            pigCounterHelper.incrCounter(ReportStats.INVALID_HISTOGRAM_BUCKET_COUNT,1L);
                        }

                        if (bucket_values.size() <= 0 ) {
                            pigCounterHelper.incrCounter(ReportStats.NO_HISTOGRAM_BUCKET_VALUES,1L);
                            validHistogram = false;
                            //newJson.put(e1.getKey(), e1.getValue());
                        } else {
                            LinkedHashMap<String, Integer> invalid_values = new LinkedHashMap<String, Integer>();
                            LinkedHashMap<String, Object> tmp = new LinkedHashMap<String, Object>();
                            List<Integer> reference_bucket_values = (List<Integer>)referenceHistograms.get(TelemetryConstants.BUCKETS);
                            for(Map.Entry<String,Object> bucket_value : bucket_values.entrySet()) {
                                int bucket_key = Integer.parseInt(bucket_value.getKey());
                                if (!reference_bucket_values.contains(bucket_key)) {
                                    invalid_values.put(TelemetryConstants.VALUES, bucket_key);
                                } else {
                                    tmp.put(TelemetryConstants.VALUES, bucket_key);
                                }
                            }
                            newJson.put(TelemetryConstants.VALUES, tmp);
                                
                            if (invalid_values.size() > 0) {
                                pigCounterHelper.incrCounter(ReportStats.INVALID_HISTOGRAM_BUCKET_VALUE,1L);
                                validHistogram = false;
                                newJson.put(TelemetryConstants.INVALID_VALUES, invalid_values);
                            }
                        }

                        intermediateJson.put(jKey, newJson);
                        if(validHistogram) {
                            pigCounterHelper.incrCounter(ReportStats.VALID_HISTOGRAM,1L);
                        } else {
                            //dump_histogram_values(jKey,referenceHistograms); 
                            pigCounterHelper.incrCounter(ReportStats.INVALID_HISTOGRAM,1L);
                        }
                        
                    } else {
                        //LOG.info("UNKNOWN_HISTOGRAM "+jKey);
                        pigCounterHelper.incrCounter(ReportStats.UNKNOWN_HISTOGRAMS,1L);
                        newJson = new LinkedHashMap<String, Object>();
                        for(Map.Entry<String, Object> e1 : d1.entrySet()) {
                            newJson.put(e1.getKey(), e1.getValue());
                            missingJson.put(jKey, newJson);
                        }
                    }
                }

                if (missingJson.size() > 0) {
                    intermediateJson.put(TelemetryConstants.MISSING_JSON_REFERENCE, missingJson);
                    finalJson.put(TelemetryConstants.HISTOGRAMS, intermediateJson);
                    return jsonMapper.writeValueAsString(finalJson);
                } else {
                    return null;
                }
                
            } else {
                pigCounterHelper.incrCounter(ReportStats.SUBMISSIONS_SKIPPED,1L);
            }

        } catch (JsonParseException e) {
            pigCounterHelper.incrCounter(ReportStats.INVALID_JSON_STRUCTURE,1L);
            e.printStackTrace();
        } catch (JsonMappingException e) {
            pigCounterHelper.incrCounter(ReportStats.INVALID_JSON_STRUCTURE,1L);
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    void dump_histogram_values(String key,Map<String,Object> referenceHistograms) {
        LOG.info(key);
        for(Map.Entry<String,Object> k : referenceHistograms.entrySet()) {
            if(!k.equals("values")) {
                LOG.info(k.getKey() + " " +k.getValue());
            }
        }

        if(referenceHistograms.containsKey("values")) {
            Map<String,Object> bucket_values = (LinkedHashMap<String,Object>)referenceHistograms.get("values");
            for(Map.Entry<String,Object> bk : bucket_values.entrySet()) {
                LOG.info(bk.getKey());
            }
        }
    }
}
 

register '/usr/lib/hbase/lib/zookeeper.jar'
register '/usr/lib/hbase/hbase-0.90.6-cdh3u4.jar'
register 'akela-0.5-SNAPSHOT.jar'
register 'telemetry-toolbox-0.2-SNAPSHOT.jar'
register 'jackson-core-2.0.6.jar'
register 'jackson-databind-2.0.6.jar'
register 'jackson-annotations-2.0.6.jar'
register 'datafu-0.0.4.jar'

SET pig.logfile telemetry_slowsql.log;
SET default_parallel 53;
SET pig.tmpfilecompression true;
SET pig.tmpfilecompression.codec lzo;
SET mapred.compress.map.output true;
SET mapred.map.output.compression.codec org.apache.hadoop.io.compress.SnappyCodec;
SET mapred.output.compress false;

define SlowSqlTuples com.mozilla.telemetry.pig.eval.SlowSqlTuples();
define Quantile datafu.pig.stats.Quantile('0.0','0.25','0.5','0.75','0.95','1.0');
define StreamingQuantile datafu.pig.stats.StreamingQuantile('0.0','0.25','0.5','0.75','0.95','1.0');

raw = LOAD 'hbase://telemetry' USING com.mozilla.pig.load.HBaseMultiScanLoader('$start_date', '$end_date', 'yyyyMMdd', 'data:json') AS (k:chararray, json:chararray);
genmap = FOREACH raw GENERATE k,com.mozilla.pig.eval.json.JsonMap(json) AS json_map:map[];
filtered_main = FILTER genmap BY json_map#'slowSQL'#'mainThread' IS NOT NULL;
mainthreads = FOREACH filtered_main GENERATE SUBSTRING(k,1,9) AS d:chararray, 
                                             (chararray)json_map#'info'#'appName' AS product:chararray,
                                             (chararray)json_map#'info'#'appVersion' AS product_version:chararray,
                                             (chararray)json_map#'info'#'appUpdateChannel' AS product_channel:chararray,
                                             FLATTEN(SlowSqlTuples(json_map#'slowSQL'#'mainThread')) AS (sql:chararray, count:long, t:long);
plus_avg_time_main = FOREACH mainthreads GENERATE d,product,product_version,product_channel,sql,count,t,((double)t / (double)count) AS avg_time:double;
grouped_main = GROUP plus_avg_time_main BY (d,product,product_version,product_channel,sql);
/* StreamingQuantile computation isn't guaranteed to be accurate with only small number of documents but is more computationaly efficient for large document sets
slowsql_main = FOREACH grouped_main GENERATE FLATTEN(group) AS (product,product_version,product_channel,app_build_id,sql),
                                             COUNT(plus_avg_time_main) AS doc_count,
                                             SUM(plus_avg_time_main.count) AS sum_count,
                                             StreamingQuantile(plus_avg_time_main.avg_time);
*/
slowsql_main = FOREACH grouped_main {
    sorted_main = ORDER plus_avg_time_main BY avg_time;
    GENERATE FLATTEN(group) AS (d,product,product_version,product_channel,sql),
             COUNT(plus_avg_time_main) AS doc_count,
             SUM(sorted_main.count) AS sum_count,
             Quantile(sorted_main.avg_time);
}
fltrd_slowsql_main = FILTER slowsql_main BY doc_count > 100;
ordered_main = ORDER fltrd_slowsql_main BY doc_count DESC,sum_count DESC;

filtered_other = FILTER genmap BY json_map#'slowSQL'#'otherThreads' IS NOT NULL;
otherthreads = FOREACH filtered_other GENERATE SUBSTRING(k,1,9) AS d:chararray,
                                               (chararray)json_map#'info'#'appName' AS product:chararray,
                                               (chararray)json_map#'info'#'appVersion' AS product_version:chararray,
                                               (chararray)json_map#'info'#'appUpdateChannel' AS product_channel:chararray,
                                               FLATTEN(SlowSqlTuples(json_map#'slowSQL'#'otherThreads')) AS (sql:chararray, count:long, t:long);
plus_avg_time_other = FOREACH otherthreads GENERATE d,product,product_version,product_channel,sql,count,t,((double)t / (double)count) AS avg_time:double;
grouped_other = GROUP plus_avg_time_other BY (d,product,product_version,product_channel,sql);
slowsql_other = FOREACH grouped_other {
    sorted_other = ORDER plus_avg_time_other BY avg_time;
    GENERATE FLATTEN(group) AS (d,product,product_version,product_channel,sql),
             COUNT(sorted_other) AS doc_count,
             SUM(sorted_other.count) AS sum_count,
             Quantile(sorted_other.avg_time);
}
fltrd_slowsql_other = FILTER slowsql_other BY doc_count > 100;
ordered_other = ORDER fltrd_slowsql_other BY doc_count DESC,sum_count DESC;

STORE ordered_main INTO 'slowsql-main-$start_date-$end_date' USING PigStorage('\u0001');
STORE ordered_other INTO 'slowsql-other-$start_date-$end_date' USING PigStorage('\u0001');

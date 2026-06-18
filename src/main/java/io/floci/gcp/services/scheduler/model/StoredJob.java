package io.floci.gcp.services.scheduler.model;


import java.util.Map;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection

public class StoredJob {

    private String name;
    private String description;

    // "PUBSUB", "HTTP" or "APP_ENGINE"
    private String targetType;

    // PubsubTarget fields
    private String pubsubTopic;
    private byte[] pubsubData;
    private Map<String, String> pubsubAttributes;

    // HttpTarget fields
    private String httpUri;
    private String httpMethod;
    private Map<String, String> httpHeaders;
    private byte[] httpBody;

    // AppEngineHttpTarget fields
    private String appEngineHttpMethod;
    private String appEngineRelativeUri;
    private Map<String, String> appEngineHeaders;
    private byte[] appEngineBody;
    private String appEngineService;
    private String appEngineVersion;
    private String appEngineInstance;
    private String appEngineHost;

    private String schedule;
    private String timeZone;

    // "ENABLED", "PAUSED", "DISABLED", "UPDATE_FAILED"
    private String state;

    // RetryConfig fields
    private int retryCount;
    private long maxRetryDurationSeconds;
    private long minBackoffSeconds;
    private long maxBackoffSeconds;
    private int maxDoublings;

    private long attemptDeadlineSeconds;

    // Timestamps (ISO-8601)
    private String createTime;
    private String userUpdateTime;
    private String scheduleTime;
    private String lastAttemptTime;

    // Last attempt result (google.rpc.Status)
    private int statusCode;
    private String statusMessage;

    public StoredJob() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String t) { this.targetType = t; }

    public String getPubsubTopic() { return pubsubTopic; }
    public void setPubsubTopic(String t) { this.pubsubTopic = t; }

    public byte[] getPubsubData() { return pubsubData; }
    public void setPubsubData(byte[] d) { this.pubsubData = d; }

    public Map<String, String> getPubsubAttributes() { return pubsubAttributes; }
    public void setPubsubAttributes(Map<String, String> a) { this.pubsubAttributes = a; }

    public String getHttpUri() { return httpUri; }
    public void setHttpUri(String u) { this.httpUri = u; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String m) { this.httpMethod = m; }

    public Map<String, String> getHttpHeaders() { return httpHeaders; }
    public void setHttpHeaders(Map<String, String> h) { this.httpHeaders = h; }

    public byte[] getHttpBody() { return httpBody; }
    public void setHttpBody(byte[] b) { this.httpBody = b; }

    public String getAppEngineHttpMethod() { return appEngineHttpMethod; }
    public void setAppEngineHttpMethod(String m) { this.appEngineHttpMethod = m; }

    public String getAppEngineRelativeUri() { return appEngineRelativeUri; }
    public void setAppEngineRelativeUri(String u) { this.appEngineRelativeUri = u; }

    public Map<String, String> getAppEngineHeaders() { return appEngineHeaders; }
    public void setAppEngineHeaders(Map<String, String> h) { this.appEngineHeaders = h; }

    public byte[] getAppEngineBody() { return appEngineBody; }
    public void setAppEngineBody(byte[] b) { this.appEngineBody = b; }

    public String getAppEngineService() { return appEngineService; }
    public void setAppEngineService(String s) { this.appEngineService = s; }

    public String getAppEngineVersion() { return appEngineVersion; }
    public void setAppEngineVersion(String v) { this.appEngineVersion = v; }

    public String getAppEngineInstance() { return appEngineInstance; }
    public void setAppEngineInstance(String i) { this.appEngineInstance = i; }

    public String getAppEngineHost() { return appEngineHost; }
    public void setAppEngineHost(String h) { this.appEngineHost = h; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String s) { this.schedule = s; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String t) { this.timeZone = t; }

    public String getState() { return state; }
    public void setState(String s) { this.state = s; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int n) { this.retryCount = n; }

    public long getMaxRetryDurationSeconds() { return maxRetryDurationSeconds; }
    public void setMaxRetryDurationSeconds(long s) { this.maxRetryDurationSeconds = s; }

    public long getMinBackoffSeconds() { return minBackoffSeconds; }
    public void setMinBackoffSeconds(long s) { this.minBackoffSeconds = s; }

    public long getMaxBackoffSeconds() { return maxBackoffSeconds; }
    public void setMaxBackoffSeconds(long s) { this.maxBackoffSeconds = s; }

    public int getMaxDoublings() { return maxDoublings; }
    public void setMaxDoublings(int n) { this.maxDoublings = n; }

    public long getAttemptDeadlineSeconds() { return attemptDeadlineSeconds; }
    public void setAttemptDeadlineSeconds(long s) { this.attemptDeadlineSeconds = s; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String t) { this.createTime = t; }

    public String getUserUpdateTime() { return userUpdateTime; }
    public void setUserUpdateTime(String t) { this.userUpdateTime = t; }

    public String getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(String t) { this.scheduleTime = t; }

    public String getLastAttemptTime() { return lastAttemptTime; }
    public void setLastAttemptTime(String t) { this.lastAttemptTime = t; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int c) { this.statusCode = c; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String m) { this.statusMessage = m; }
}

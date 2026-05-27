package io.floci.gcp.services.tasks.model;


import java.util.Map;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection

public class StoredTask {

    private String name;
    private String createTime;
    private String scheduleTime;
    private int dispatchCount;
    private int responseCount;

    // "HTTP" or "APP_ENGINE"
    private String taskType;

    // HttpRequest fields
    private String httpMethod;
    private String url;
    private Map<String, String> headers;
    private byte[] body;

    // AppEngineHttpRequest fields
    private String appEngineHttpMethod;
    private String relativeUri;

    public StoredTask() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String t) { this.createTime = t; }

    public String getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(String t) { this.scheduleTime = t; }

    public int getDispatchCount() { return dispatchCount; }
    public void setDispatchCount(int n) { this.dispatchCount = n; }

    public int getResponseCount() { return responseCount; }
    public void setResponseCount(int n) { this.responseCount = n; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String t) { this.taskType = t; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String m) { this.httpMethod = m; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> h) { this.headers = h; }

    public byte[] getBody() { return body; }
    public void setBody(byte[] b) { this.body = b; }

    public String getAppEngineHttpMethod() { return appEngineHttpMethod; }
    public void setAppEngineHttpMethod(String m) { this.appEngineHttpMethod = m; }

    public String getRelativeUri() { return relativeUri; }
    public void setRelativeUri(String u) { this.relativeUri = u; }
}

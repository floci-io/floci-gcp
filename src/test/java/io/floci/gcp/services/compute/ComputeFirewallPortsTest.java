package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.services.compute.model.ComputeProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComputeFirewallPortsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sshAlwaysAndTheTcpPortsOfMatchingIngressRules() throws Exception {
        ComputeProject state = new ComputeProject();
        rule(state, "all", "{\"network\":\"https://www.googleapis.com/compute/v1/projects/p/global/networks/net\","
                + "\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"443\"]},{\"IPProtocol\":\"icmp\"}]}");
        rule(state, "tagged", "{\"network\":\"global/networks/net\",\"targetTags\":[\"web\"],"
                + "\"allowed\":[{\"IPProtocol\":\"TCP\",\"ports\":[\"8000-8002\"]}]}");
        rule(state, "other-tag", "{\"network\":\"global/networks/net\",\"targetTags\":[\"db\"],"
                + "\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"5432\"]}]}");
        rule(state, "other-net", "{\"network\":\"global/networks/elsewhere\",\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"1\"]}]}");
        rule(state, "egress", "{\"network\":\"global/networks/net\",\"direction\":\"EGRESS\","
                + "\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"2\"]}]}");
        rule(state, "disabled", "{\"network\":\"global/networks/net\",\"disabled\":true,"
                + "\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"3\"]}]}");

        assertEquals(List.of(22, 443, 8000, 8001, 8002), ComputeFirewallPorts.forInstance(state, instance("web")));
        assertEquals(List.of(22, 443), ComputeFirewallPorts.forInstance(state, instance("api")));
    }

    @Test
    void aWideRangeIsCappedAtTheMaximum() throws Exception {
        ComputeProject state = new ComputeProject();
        rule(state, "wide", "{\"network\":\"global/networks/net\",\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"1000-5000\"]}]}");

        List<Integer> ports = ComputeFirewallPorts.forInstance(state, instance("web"));

        assertEquals(ComputeFirewallPorts.MAX_PORTS, ports.size());
        assertEquals(22, ports.getFirst());
    }

    private static void rule(ComputeProject state, String name, String json) throws Exception {
        state.resources.put("global/firewalls/" + name, (ObjectNode) JSON.readTree(json));
    }

    private static ObjectNode instance(String tag) throws Exception {
        return (ObjectNode) JSON.readTree("{\"name\":\"vm\",\"tags\":{\"items\":[\"" + tag + "\"]},"
                + "\"networkInterfaces\":[{\"network\":\"https://www.googleapis.com/compute/v1/projects/p/global/networks/net\"}]}");
    }
}

/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.FlowStats;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.MidoPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;

public class PingTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(PingTest.class);

    static Tenant tenant1;
    static IntIPv4 rtrIp;
    static IntIPv4 ip1;
    static IntIPv4 ip2;
    static IntIPv4 ip3;
    static MidoPort p1;
    static MidoPort p2;
    static MidoPort p3;
    static TapWrapper tap1;
    static TapWrapper tap2;
    static OpenvSwitchDatabaseConnectionImpl ovsdb;
    static PacketHelper helper1;
    static PacketHelper helper2;
    static MidolmanMgmt mgmt;
    static MidolmanLauncher midolman;
    static OvsBridge ovsBridge;
    static ServiceController svcController;

    @Before
    public void setUp() throws InterruptedException, IOException {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start();

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);
        // Add a service controller to OVS bridge 1.
        ovsBridge.addServiceController(6640);
        Thread.sleep(1000);
        svcController = new ServiceController(6640);

        rtrIp = IntIPv4.fromString("192.168.231.1");
        ip1 = IntIPv4.fromString("192.168.231.2");
        ip2 = IntIPv4.fromString("192.168.231.3");
        ip3 = IntIPv4.fromString("192.168.231.4");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant").build();
        Router rtr = tenant1.addRouter().setName("rtr1").build();

        p1 = rtr.addVmPort().setVMAddress(ip1).build();
        tap1 = new TapWrapper("pingTestTap1");
        ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());

        p2 = rtr.addVmPort().setVMAddress(ip2).build();
        tap2 = new TapWrapper("pingTestTap2");
        ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());

        p3 = rtr.addVmPort().setVMAddress(ip3).build();
        ovsBridge.addInternalPort(p3.port.getId(), "pingTestInt", ip3, 24);

        helper1 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:01"), ip1,
                tap1.getHwAddr(), rtrIp);
        helper2 = new PacketHelper(MAC.fromString("02:00:00:aa:aa:02"), ip2,
                tap2.getHwAddr(), rtrIp);

        Thread.sleep(10 * 1000);
    }

    @After
    public void tearDown() throws InterruptedException {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);
        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
        stopMidolman(midolman);
        removeBridge(ovsBridge);
    }

    @Test
    public void test() {
        synchronized(mgmt) {
        byte[] request;

        // First arp for router's mac.
        assertThat("The ARP request was sent properly",
                   tap1.send(helper1.makeArpRequest()));

        helper1.checkArpReply(tap1.recv());

        // Ping router's port.
        request = helper1.makeIcmpEchoRequest(rtrIp);
        assertThat("The tap should have sent the packet", tap1.send(request));
        // Note: Midolman's virtual router currently does not ARP before
        // responding to ICMP echo requests addressed to its own port.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // Ping internal port p3.
        request = helper1.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet again",
                tap1.send(request));
        // Note: the virtual router ARPs before delivering the reply packet.
        helper1.checkArpRequest(tap1.recv());
        assertThat("The tap should have sent the packet again",
                tap1.send(helper1.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(request, tap1.recv());

        // No other packets arrive at the port.
        assertNull(tap1.recv());
        } // end synchronized(mgmt)
    }

    @Test
    public void testPortDelete() throws InterruptedException {
        synchronized(mgmt) {
        short num1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        short num2 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        short num3 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID("pingTestInt"));
        log.debug("The OF ports have numbers: {}, {}, and {}", new Object[] {
                num1, num2, num3});

        // Remove/re-add the two tap ports to remove all flows.
        ovsBridge.deletePort(tap1.getName());
        ovsBridge.deletePort(tap2.getName());
        // Sleep to let OVS complete the port requests.
        Thread.sleep(1000);
        ovsBridge.addSystemPort(p1.port.getId(), tap1.getName());
        ovsBridge.addSystemPort(p2.port.getId(), tap2.getName());
        // Sleep to let OVS complete the port requests.
        Thread.sleep(1000);
        num1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        num2 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap2.getName()));
        num3 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID("pingTestInt"));
        log.debug("The OF ports have numbers: {}, {}, and {}", new Object[] {
                num1, num2, num3});

        // Verify that there are no ICMP flows.
        MidoMatch icmpMatch = new MidoMatch()
                .setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        List<FlowStats> fstats = svcController.getFlowStats(icmpMatch);
        assertEquals(0, fstats.size());

        // Send ICMPs from p1 to internal port p3.
        byte[] ping1_3 = helper1.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet", tap1.send(ping1_3));
        // Note: the virtual router ARPs before delivering the reply packet.
        helper1.checkArpRequest(tap1.recv());
        assertThat("The tap should have sent the ARP reply",
                tap1.send(helper1.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping1_3, tap1.recv());

        // Send ICMPs from p2 to internal port p3.
        byte[] ping2_3 = helper2.makeIcmpEchoRequest(ip3);
        assertThat("The tap should have sent the packet", tap2.send(ping2_3));
        // Note: the virtual router ARPs before delivering the reply packet.
        helper2.checkArpRequest(tap2.recv());
        assertThat("The tap should have sent the ARP reply",
                tap2.send(helper2.makeArpReply()));
        // Finally, the icmp echo reply from the peer.
        PacketHelper.checkIcmpEchoReply(ping2_3, tap2.recv());

        // Sleep to let OVS update its stats.
        Thread.sleep(1000);

        // Now there should be 4 ICMP flows
        fstats = svcController.getFlowStats(icmpMatch);
        assertEquals(4, fstats.size());

        // Verify that there are flows: 1->3, 3->1, 2->3, 3->2.
        fstats = svcController.getFlowStats(new MidoMatch().setNetworkSource(
                ip1.address).setNetworkDestination(ip3.address));
        assertEquals(1, fstats.size());
        FlowStats flow1_3 = fstats.get(0);
        flow1_3.expectCount(1).expectOutputAction(num3);

        fstats = svcController.getFlowStats(new MidoMatch().setNetworkSource(
                ip3.address).setNetworkDestination(ip1.address));
        assertEquals(1, fstats.size());
        FlowStats flow3_1 = fstats.get(0);
        flow3_1.expectCount(1).expectOutputAction(num1);

        fstats = svcController.getFlowStats(new MidoMatch().setNetworkSource(
                ip2.address).setNetworkDestination(ip3.address));
        assertEquals(1, fstats.size());
        FlowStats flow2_3 = fstats.get(0);
        flow2_3.expectCount(1).expectOutputAction(num3);

        fstats = svcController.getFlowStats(new MidoMatch().setNetworkSource(
                ip3.address).setNetworkDestination(ip2.address));
        assertEquals(1, fstats.size());
        FlowStats flow3_2 = fstats.get(0);
        flow3_2.expectCount(1).expectOutputAction(num2);

        // Repeat the pings and verify that the packet counts increase.
        assertThat("The tap should have sent the packet", tap1.send(ping1_3));
        assertThat("The tap should have sent the packet", tap2.send(ping2_3));
        // Sleep to let OVS update its stats.
        Thread.sleep(1000);

        // Verify that the flow counts have increased as expected.
        flow1_3.findSameInList(svcController.getFlowStats(flow1_3.getMatch()))
                .expectCount(2).expectOutputAction(num3);
        flow3_1.findSameInList(svcController.getFlowStats(flow3_1.getMatch()))
                .expectCount(2).expectOutputAction(num1);
        flow2_3.findSameInList(svcController.getFlowStats(flow2_3.getMatch()))
                .expectCount(2).expectOutputAction(num3);
        flow3_2.findSameInList(svcController.getFlowStats(flow3_2.getMatch()))
                .expectCount(2).expectOutputAction(num2);

        // Now remove p3 and verify that all flows are removed since they
        // are ingress or egress at p3.
        ovsBridge.deletePort("pingTestInt");
        Thread.sleep(2000);
        fstats = svcController.getFlowStats(icmpMatch);
        assertEquals(0, fstats.size());
        } // end synchronized(mgmt)
    }
}

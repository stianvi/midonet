/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeSet

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.Topology.{Port, Route, ServiceContainer}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.services.c3po.translators.IPSecSiteConnectionTranslator._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers
import org.midonet.packets.IPv4Subnet

class IPSecSiteConnectionTranslator
    extends Translator[IPSecSiteConnection] with RouteManager {

    override protected def translateCreate(tx: Transaction,
                                           cnxn: IPSecSiteConnection): Unit = {
        val vpn = tx.get(classOf[VpnService], cnxn.getVpnserviceId)
        tx.create(cnxn)
        createRemoteRoutes(tx, cnxn, vpn).foreach(r => tx.create(r))
    }

    override protected def translateDelete(tx: Transaction,
                                           cnxn: IPSecSiteConnection): Unit = {
        tx.delete(classOf[IPSecSiteConnection], cnxn.getId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           cnxn: IPSecSiteConnection): Unit = {
        val vpn = tx.get(classOf[VpnService], cnxn.getVpnserviceId)
        val (delRoutes, addRoutes, currentRoutes) = updateRemoteRoutes(tx, cnxn, vpn)

        val newCnxn = cnxn.toBuilder
            .addAllRouteIds(currentRoutes.map(_.asProto).asJava).build

        delRoutes.foreach(r => tx.delete(classOf[Route], r.getId, ignoresNeo = true))
        addRoutes.foreach(r => tx.create(r))
        tx.update(newCnxn)

    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[IPSecSiteConnection])
    : List[Operation[IPSecSiteConnection]] = List()

    /** Generate options to create routes for Cartesian product of cnxn's
      * local CIDRs and peer CIDRs.
      */
    private def createRemoteRoutes(tx: Transaction,
                                   cnxn: IPSecSiteConnection,
                                   vpn: VpnService): Seq[Route] = {

        def findPortSubnet(port: Port): IPv4Subnet = {
            for (subnet <- port.getPortSubnetList.asScala) {
                if (subnet.getVersion == IPVersion.V4) {
                    return IPSubnetUtil.fromV4Proto(subnet)
                }
            }
            null
        }

        val container = tx.get(classOf[ServiceContainer],
                               vpn.getContainerId)
        val routerPort = tx.get(classOf[Port],
                                container.getPortId)
        val routerPortId = routerPort.getId
        val routerPortSubnet = findPortSubnet(routerPort)

        if (routerPortSubnet eq null) {
            return Seq.empty
        }

        val localPeerCidrPairs = for {
            localCidr <- cnxn.getLocalCidrsList.asScala
            peerCidr <- cnxn.getPeerCidrsList.asScala
        } yield (localCidr, peerCidr)


        localPeerCidrPairs.map { case (localCidr, peerCidr) =>
            newNextHopPortRoute(
                id = UUIDUtil.randomUuidProto,
                ipSecSiteConnectionId = cnxn.getId,
                nextHopPortId = routerPortId,
                nextHopGateway =
                    containers.containerPortAddress(routerPortSubnet).asProto,
                srcSubnet = localCidr,
                dstSubnet = peerCidr)
        }.toList
    }

    private def updateRemoteRoutes(tx: Transaction,
                                   cnxn: IPSecSiteConnection,
                                   vpn: VpnService): (List[Route],
                                                      List[Route],
                                                      List[UUID]) = {

        val oldCnxn = tx.get(classOf[IPSecSiteConnection], cnxn.getId)
        val oldRoutes = TreeSet(tx.getAll(classOf[Route],
                                oldCnxn.getRouteIdsList) : _*) (routeOrdering)
        val newRoutes = TreeSet(createRemoteRoutes(tx, cnxn, vpn): _*) (routeOrdering)

        val toDelete = oldRoutes &~ newRoutes
        val toCreate = newRoutes &~ oldRoutes
        // keep the old route ids + the new ones
        val updatedIds = ((oldRoutes & newRoutes) | toCreate).map(_.getId.asJava)
        (toDelete.toList, toCreate.toList, updatedIds.toList)
    }
}

object IPSecSiteConnectionTranslator {

    private val routeOrdering = scala.Ordering.fromLessThan[Route](
        (a: Route, b: Route) => {
            a.getSrcSubnet != b.getSrcSubnet ||
            a.getDstSubnet != b.getDstSubnet
        })
}

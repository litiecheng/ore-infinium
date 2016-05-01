package com.ore.infinium.systems.server

import com.artemis.BaseSystem
import com.artemis.ComponentMapper
import com.artemis.annotations.Wire
import com.ore.infinium.OreWorld
import com.ore.infinium.PowerCircuit
import com.ore.infinium.PowerCircuitHelper
import com.ore.infinium.PowerWireConnection
import com.ore.infinium.components.*
import com.ore.infinium.util.getNullable

/**
 * ***************************************************************************
 * Copyright (C) 2015 by Shaun Reich @gmail.com>                    *
 * *
 * This program is free software; you can redistribute it and/or            *
 * modify it under the terms of the GNU General Public License as           *
 * published by the Free Software Foundation; either version 2 of           *
 * the License, or (at your option) any later version.                      *
 * *
 * This program is distributed in the hope that it will be useful,          *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of           *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            *
 * GNU General Public License for more details.                             *
 * *
 * You should have received a copy of the GNU General Public License        *
 * along with this program.  If not, see //www.gnu.org/licenses/>.    *
 * ***************************************************************************
 */


/**
 * system that handled all the power circuits/wire connections, can look them up,
 * connect them, remove them, etc., and it also will process them each tick (if server) and
 * calculate their current statuses, e.g. how much electricity was generated,
 * consumed, etc...
 *
 *
 * This is a server-only system
 */
@Wire
class ServerPowerCircuitSystem(private val m_world: OreWorld) : BaseSystem() {

    /**
     * serves as a global (cross-network) identifier
     * for circuits (this always points to the next unique id)
     */
    private var m_nextCircuitId = 0

    /**
     * Contains list of each circuit in the world.
     *
     *
     * A circuit contains all the wire connections that are continuous/connected
     * in some form. Circuits would probably average 20 or so unique devices
     * But it could be much much more (and probably will be)
     *
     *
     * When devices that are on different circuits get connected, those
     * devices are merged into the same circuit
     */
    var m_circuits = mutableListOf<PowerCircuit>()

    private lateinit var playerMapper: ComponentMapper<PlayerComponent>
    private lateinit var spriteMapper: ComponentMapper<SpriteComponent>
    private lateinit var itemMapper: ComponentMapper<ItemComponent>
    private lateinit var velocityMapper: ComponentMapper<VelocityComponent>
    private lateinit var powerDeviceMapper: ComponentMapper<PowerDeviceComponent>
    private lateinit var powerConsumerMapper: ComponentMapper<PowerConsumerComponent>
    private lateinit var powerGeneratorMapper: ComponentMapper<PowerGeneratorComponent>

    private lateinit var m_serverNetworkSystem: ServerNetworkSystem

    val m_powerCircuitHelper = PowerCircuitHelper()

    override fun initialize() {
        getWorld().inject(m_powerCircuitHelper, true)
    }

    /**
     * Process the system.
     */
    override fun processSystem() {
        /*
        * note that only the server should be the one that processes input and
        * output for generators, devices etc...the client cannot accurately calculate this each tick,
        * without desyncing at some point. the server should be the one
        * informing it of the outcomes, and the changes can be sent over the
        * wire and consumed by the clientside system system
        */

        calculateSupplyAndDemandRates()
    }

    private fun calculateSupplyAndDemandRates() {
        for (circuit in m_circuits) {
            circuit.totalDemand = 0
            circuit.totalSupply = 0

            for (generator in circuit.generators) {
                val generatorComponent = powerGeneratorMapper.get(generator)
                circuit.totalSupply += generatorComponent.powerSupplyRate
            }

            for (consumer in circuit.consumers) {
                val consumerComponent = powerConsumerMapper.get(consumer)
                circuit.totalDemand += consumerComponent.powerDemandRate
            }
        }
    }

    //fixme does not inform the server of these connections!!! or anything wirey for that matter.

    /**
     * connects two power devices together, determines how to handle data structures
     * in between.
     *
     * will only perform connection if it is allowed, as in the devices can be connected,
     * and the player is allowed to connect them (due to various states, like out of range,
     * being possible)

     * @param firstEntity
     * *
     * @param secondEntity
     *
     * @return false if connection failed/not allowed
     */
    fun connectDevices(firstEntity: Int, secondEntity: Int, playerEntityId: Int? = null): Boolean {
        when {
        //disallow connection with itself
            firstEntity == secondEntity ->
                return false

        //don't allow connecting wires between any dropped item devices
            areDevicesDroppedItems(firstEntity, secondEntity) ->
                return false

        //don't allow connecting two devices that are already connected together
            entitiesConnected(firstEntity, secondEntity) ->
                return false

            playerEntityId != null && !playerWithinRangeToConnectDevices(firstEntity, secondEntity, playerEntityId) ->
                return false

        //todo verify some other conditions that the player would be restricted in placing wires
        //like has enough materials, or something like that.
        }


        val firstOwningCircuit = powerDeviceMapper.get(firstEntity).owningCircuit
        val secondOwningCircuit = powerDeviceMapper.get(secondEntity).owningCircuit

        when {
            firstOwningCircuit != null && secondOwningCircuit != null -> {//merge circuits that contain these entities.
                m_powerCircuitHelper.mergeCircuits(firstEntity, secondEntity, firstOwningCircuit, secondOwningCircuit,
                                                   m_circuits)
                return true
            }

            firstOwningCircuit != null && secondOwningCircuit == null -> {
                m_powerCircuitHelper.addWireConnection(firstEntity, secondEntity, firstOwningCircuit)
                return true
            }

            secondOwningCircuit != null && firstOwningCircuit == null -> {
                m_powerCircuitHelper.addWireConnection(firstEntity, secondEntity, secondOwningCircuit)
                return true
            }
            else -> {
                //no circuits
                val circuit = PowerCircuit(m_nextCircuitId++)

                m_powerCircuitHelper.addWireConnection(firstEntity, secondEntity, circuit)
                m_circuits.add(circuit)

                return true
            }
        }
    }

    private fun playerWithinRangeToConnectDevices(firstEntity: Int, secondEntity: Int, playerEntityId: Int): Boolean {
        //fixme, not implemented. probably just ensure they're not talking about entities off screen(not exists
        //in player viewport
        return true
    }


    /**
     * @return true if one of the devices is dropped in the world
     */
    private fun areDevicesDroppedItems(firstEntity: Int, secondEntity: Int): Boolean {
        when {
            itemMapper.getNullable(firstEntity)!!.state == ItemComponent.State.DroppedInWorld ||
                    itemMapper.getNullable(secondEntity)!!.state == ItemComponent.State.DroppedInWorld
            -> return true
            else -> return false
        }

    }

    /**
     * scans all circuits and connections within each circuit, to see if they
     * are already connected.
     * This is so that e.g. device 1 and device 2 cannot have two connections
     * to each other.
     *
     * @return true if these are already connected in some circuit. false if
     * they are not connected to each other (but could be connected to something else)
     */
    private fun entitiesConnected(firstEntity: Int, secondEntity: Int): Boolean {
        return m_circuits.any { circuit ->
            circuit.wireConnections.any { wire ->
                m_powerCircuitHelper.isWireConnectedToAllDevices(wire, firstEntity, secondEntity)
            }
        }
    }

    fun disconnectWire(wireId: Int, circuitId: Int, playerEntityId: Int): Boolean {
        //todo use playerid to see if it is within their valid range (and not deleting arbitrary wires

        var foundWire: PowerWireConnection? = null
        val result = m_circuits.any { circuit ->
            //todo this removes immediately. we need to see if it exists, then verify..something.. i think?
            //maybe not.
            circuit.circuitId == circuitId && circuit.wireConnections.removeAll { itWire ->
                foundWire = itWire
                itWire.wireId == wireId
            }
        }

        val deviceComp1 = powerDeviceMapper.get(foundWire!!.firstEntity)
        val deviceComp2 = powerDeviceMapper.get(foundWire!!.secondEntity)

        //remove the wire we're referring to, in both entity's device comp wire list
        //(since dev A (wire)-> dev B, both would have that wire id in them.
        deviceComp1.wireIdsConnectedIn.removeAll { itWireId ->
            itWireId == wireId
        }

        deviceComp2.wireIdsConnectedIn.removeAll { itWireId ->
            itWireId == wireId
        }

        deviceComp1.circuitId = PowerCircuitHelper.INVALID_CIRCUITID
        deviceComp2.circuitId = PowerCircuitHelper.INVALID_CIRCUITID

        //todo find the devices that are being referred to, ensure they have no connections on them,
        //then set their device identifiers to invalid! client shall do the same when it receives disconnect!!
        if (result) {
            m_serverNetworkSystem.sendPowerWireDisconnect(wireId, circuitId)
        }

        when {
            result -> {
                m_powerCircuitHelper.cleanupDeadCircuits(m_circuits)
                return true
            }
            else -> return false
        }
    }

    //todo sufficient until we get a spatial hash or whatever

    /*
    private Entity entityAtPosition(Vector2 pos) {

        ImmutableArray<Entity> entities = m_world.engine.getEntitiesFor(Family.all(PowerComponent.class).get());
        SpriteComponent spriteComponent;
        TagComponent tagComponent;
        for (int i = 0; i < entities.size(); ++i) {
            tagComponent = tagMapper.get(entities.get(i));

            if (tagComponent != null && tagComponent.tag.equals("itemPlacementOverlay")) {
                continue;
            }

            spriteComponent = spriteMapper.get(entities.get(i));

            Rectangle rectangle = new Rectangle(spriteComponent.sprite.getX() - (spriteComponent.sprite.getWidth() *
            0.5f),
                    spriteComponent.sprite.getY() - (spriteComponent.sprite.getHeight() * 0.5f),
                    spriteComponent.sprite.getWidth(), spriteComponent.sprite.getHeight());

            if (rectangle.contains(pos)) {
                return entities.get(i);
            }
        }

        return null;
    }

    public void update(float delta) {

    }
    */
}
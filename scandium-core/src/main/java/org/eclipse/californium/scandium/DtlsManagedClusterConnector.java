/*******************************************************************************
 * Copyright (c) 2020 Bosch.IO GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch IO.GmbH - initial creation
 ******************************************************************************/
package org.eclipse.californium.scandium;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import javax.crypto.SecretKey;

import org.eclipse.californium.elements.ExtendedConnector;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.UDPConnector;
import org.eclipse.californium.scandium.config.DtlsClusterConnectorConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.SessionCache;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedSinglePskStore;
import org.eclipse.californium.scandium.util.SecretUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DTLS dynamic cluster connector.
 * 
 * Discover and update cluster cid nodes associations dynamically.
 * 
 * @since 2.5
 */
public class DtlsManagedClusterConnector extends DtlsClusterConnector {

	private static final Logger LOGGER = LoggerFactory.getLogger(DtlsManagedClusterConnector.class);

	public static final String PROTOCOL_MANAGEMENT_UDP = "mgmt-udp";
	public static final String PROTOCOL_MANAGEMENT_DTLS = "mgmt-dtls";

	/**
	 * Protocol for cluster management.
	 */
	private final String protocol;
	/**
	 * Connector for cluster management.
	 */
	private volatile ExtendedConnector clusterManagementConnector;

	/**
	 * Create dtls connector with dynamic cluster support.
	 * 
	 * @param configuration dtls configuration
	 * @param clusterConfiguration cluster internal connector configuration
	 */
	public DtlsManagedClusterConnector(DtlsConnectorConfig configuration,
			DtlsClusterConnectorConfig clusterConfiguration) {
		this(configuration, clusterConfiguration, null);
	}

	/**
	 * Create dtls connector with dynamic cluster support.
	 * 
	 * @param configuration dtls configuration
	 * @param clusterConfiguration cluster internal connector configuration
	 * @param sessionCache session cache
	 */
	public DtlsManagedClusterConnector(DtlsConnectorConfig configuration,
			DtlsClusterConnectorConfig clusterConfiguration, SessionCache sessionCache) {
		super(configuration, clusterConfiguration, sessionCache);
		String identity = clusterConfiguration.getSecureIdentity();
		if (identity == null) {
			this.protocol = PROTOCOL_MANAGEMENT_UDP;
		} else {
			this.protocol = PROTOCOL_MANAGEMENT_DTLS;
		}
		Integer mgmtReceiveBuffer = add(config.getSocketReceiveBufferSize(), DATAGRAM_OFFSET);
		Integer mgmtSendBuffer = add(config.getSocketSendBufferSize(), DATAGRAM_OFFSET);
		LOGGER.info("cluster-node {} ({}): recv. buffer {}, send buffer {}", getNodeID(), protocol, mgmtReceiveBuffer,
				mgmtSendBuffer);
		if (identity != null) {
			SecretKey secretkey = clusterConfiguration.getSecretKey();
			DtlsConnectorConfig.Builder builder = DtlsConnectorConfig.builder()
					.setAddress(clusterConfiguration.getAddress()).setReceiverThreadCount(0).setMaxConnections(1024)
					.setSocketReceiveBufferSize(mgmtReceiveBuffer).setSocketSendBufferSize(mgmtSendBuffer)
					.setAdvancedPskStore(new AdvancedSinglePskStore(identity, secretkey));
			clusterManagementConnector = new ClusterManagementDtlsConnector(builder.build());
			SecretUtil.destroy(secretkey);
		} else {
			ClusterManagementUdpConnector connector = new ClusterManagementUdpConnector(
					clusterConfiguration.getAddress());
			connector.setReceiverThreadCount(0);
			connector.setSenderThreadCount(2);
			if (mgmtReceiveBuffer != null) {
				connector.setReceiveBufferSize(mgmtReceiveBuffer);
			}
			if (mgmtSendBuffer != null) {
				connector.setSendBufferSize(mgmtSendBuffer);
			}
			clusterManagementConnector = connector;
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 * Creates socket and threads for cluster internal communication.
	 */
	@Override
	protected void init(InetSocketAddress bindAddress, DatagramSocket socket, Integer mtu) throws IOException {
		super.init(bindAddress, socket, mtu);
		clusterManagementConnector.start();
		startReceiver();
	}

	@Override
	public void stop() {
		clusterManagementConnector.stop();
		super.stop();
	}

	/**
	 * Get protocol for management connector.
	 * 
	 * @return {@link #PROTOCOL_MANAGEMENT_UDP} or
	 *         {@link #PROTOCOL_MANAGEMENT_DTLS}.
	 */
	public String getManagementProtocol() {
		return protocol;
	}

	/**
	 * Get cluster management connector.
	 * 
	 * @return cluster management connector
	 */
	public ExtendedConnector getClusterManagementConnector() {
		return clusterManagementConnector;
	}

	@Override
	protected void processManagementDatagramFromClusterNetwork(DatagramPacket clusterPacket) throws IOException {
		LOGGER.trace("cluster-node {} ({}): process datagram from {}, {} bytes", getNodeID(), protocol,
				clusterPacket.getAddress(), clusterPacket.getLength());
		clusterManagementConnector.processDatagram(clusterPacket);
	}

	/**
	 * Add two values.
	 * 
	 * @param value value, if {@code null} or {@code 0}, don't add the second
	 *            value.
	 * @param add additional value.
	 * @return added value
	 */
	private static Integer add(Integer value, int add) {
		if (value != null && value != 0) {
			return value + add;
		} else {
			return value;
		}
	}

	/**
	 * Cluster management connector using UDP.
	 */
	private class ClusterManagementUdpConnector extends UDPConnector implements ExtendedConnector {

		public ClusterManagementUdpConnector(InetSocketAddress bindAddress) {
			super(bindAddress);
		}

		@Override
		public synchronized void start() throws IOException {
			if (isRunning())
				return;
			init(clusterInternalSocket);
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public void processDatagram(DatagramPacket datagram) {
			super.processDatagram(datagram);
			if (clusterHealth != null) {
				clusterHealth.receivingClusterManagementMessage();
			}
		}

		@Override
		public void send(RawData msg) {
			super.send(msg);
			if (clusterHealth != null) {
				clusterHealth.sendingClusterManagementMessage();
			}
		}

	}

	/**
	 * Cluster management connector using DTLS.
	 */
	private class ClusterManagementDtlsConnector extends DTLSConnector implements ExtendedConnector {

		public ClusterManagementDtlsConnector(DtlsConnectorConfig configuration) {
			super(configuration);
		}

		@Override
		protected void start(InetSocketAddress bindAddress) throws IOException {
			if (isRunning()) {
				return;
			}
			init(bindAddress, clusterInternalSocket, config.getMaxTransmissionUnit());
		}

		@Override
		public void processDatagram(DatagramPacket datagram) {
			super.processDatagram(datagram, null);
			if (clusterHealth != null) {
				clusterHealth.receivingClusterManagementMessage();
			}
		}

		@Override
		public void send(RawData msg) {
			super.send(msg);
			if (clusterHealth != null) {
				clusterHealth.sendingClusterManagementMessage();
			}
		}

	}

}

/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zigbee.internal.converter;

import static org.eclipse.smarthome.core.library.unit.MetricPrefix.HECTO;

import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;

import javax.measure.quantity.Pressure;

import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.openhab.binding.zigbee.ZigBeeBindingConstants;
import org.openhab.binding.zigbee.converter.ZigBeeBaseChannelConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zsmartsystems.zigbee.CommandResult;
import com.zsmartsystems.zigbee.ZigBeeEndpoint;
import com.zsmartsystems.zigbee.zcl.ZclAttribute;
import com.zsmartsystems.zigbee.zcl.ZclAttributeListener;
import com.zsmartsystems.zigbee.zcl.clusters.ZclPressureMeasurementCluster;
import com.zsmartsystems.zigbee.zcl.protocol.ZclClusterType;

/**
 * Converter for the atmospheric pressure channel.
 * This channel will attempt to detect if the device is supporting the enhanced (scaled) value reports and use them if
 * they are available.
 *
 * @author Chris Jackson - Initial Contribution
 *
 */
public class ZigBeeConverterAtmosphericPressure extends ZigBeeBaseChannelConverter implements ZclAttributeListener {
    private Logger logger = LoggerFactory.getLogger(ZigBeeConverterAtmosphericPressure.class);

    private ZclPressureMeasurementCluster cluster;

    /**
     * If enhancedScale is null, then the binding will use the MeasuredValue report,
     * otherwise it will use the ScaledValue report
     */
    private Integer enhancedScale = null;

    @Override
    public synchronized boolean initializeConverter() {
        cluster = (ZclPressureMeasurementCluster) endpoint.getInputCluster(ZclPressureMeasurementCluster.CLUSTER_ID);
        if (cluster == null) {
            logger.error("{}: Error opening device pressure measurement cluster", endpoint.getIeeeAddress());
            return false;
        }

        // Check if the enhanced attributes are supported
        if (cluster.getScaledValue(Long.MAX_VALUE) != null) {
            enhancedScale = cluster.getScale(Long.MAX_VALUE);
            if (enhancedScale != null) {
                enhancedScale *= -1;
            }
        }

        bind(cluster);

        // Add a listener, then request the status
        cluster.addAttributeListener(this);

        // Configure reporting - no faster than once per second - no slower than 2 hours.
        try {
            CommandResult reportingResponse;
            if (enhancedScale != null) {
                reportingResponse = cluster.setScaledValueReporting(1, REPORTING_PERIOD_DEFAULT_MAX, 0.1).get();
                handleReportingResponse(reportingResponse, POLLING_PERIOD_DEFAULT, REPORTING_PERIOD_DEFAULT_MAX);
            } else {
                reportingResponse = cluster.setMeasuredValueReporting(1, REPORTING_PERIOD_DEFAULT_MAX, 0.1).get();
                handleReportingResponse(reportingResponse, POLLING_PERIOD_DEFAULT, REPORTING_PERIOD_DEFAULT_MAX);
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.error("{}: Exception setting reporting ", endpoint.getIeeeAddress(), e);
        }

        return true;
    }

    @Override
    public void disposeConverter() {
        cluster.removeAttributeListener(this);
    }

    @Override
    public void handleRefresh() {
        if (enhancedScale != null) {
            cluster.getScaledValue(0);
        } else {
            cluster.getMeasuredValue(0);
        }
    }

    @Override
    public Channel getChannel(ThingUID thingUID, ZigBeeEndpoint endpoint) {
        if (endpoint.getInputCluster(ZclPressureMeasurementCluster.CLUSTER_ID) == null) {
            logger.trace("{}: Pressure measurement cluster not found", endpoint.getIeeeAddress());
            return null;
        }

        return ChannelBuilder
                .create(createChannelUID(thingUID, endpoint, ZigBeeBindingConstants.CHANNEL_NAME_PRESSURE_VALUE),
                        ZigBeeBindingConstants.ITEM_TYPE_NUMBER_PRESSURE)
                .withType(ZigBeeBindingConstants.CHANNEL_PRESSURE_VALUE)
                .withLabel(ZigBeeBindingConstants.CHANNEL_LABEL_PRESSURE_VALUE)
                .withProperties(createProperties(endpoint)).build();
    }

    @Override
    public synchronized void attributeUpdated(ZclAttribute attribute) {
        logger.debug("{}: ZigBee attribute reports {}", endpoint.getIeeeAddress(), attribute);
        if (attribute.getCluster() != ZclClusterType.PRESSURE_MEASUREMENT) {
            return;
        }

        // Handle automatic reporting of the enhanced attribute configuration
        if (attribute.getId() == ZclPressureMeasurementCluster.ATTR_SCALE) {
            enhancedScale = (Integer) attribute.getLastValue();
            if (enhancedScale != null) {
                enhancedScale *= -1;
            }
            return;
        }

        if (attribute.getId() == ZclPressureMeasurementCluster.ATTR_SCALEDVALUE && enhancedScale != null) {
            Integer value = (Integer) attribute.getLastValue();
            if (value != null) {
                updateChannelState(
                        new QuantityType<Pressure>(BigDecimal.valueOf(value, enhancedScale), HECTO(SIUnits.PASCAL)));
            }
            return;
        }

        if (attribute.getId() == ZclPressureMeasurementCluster.ATTR_MEASUREDVALUE && enhancedScale == null) {
            Integer value = (Integer) attribute.getLastValue();
            if (value != null) {
                updateChannelState(new QuantityType<Pressure>(BigDecimal.valueOf(value, 0), HECTO(SIUnits.PASCAL)));
            }
            return;
        }
    }
}

/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/

package org.ofbiz.manufacturing.mrp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.manufacturing.bom.BOMTree;
import org.ofbiz.manufacturing.jobshopmgt.ProductionRun;
import org.ofbiz.manufacturing.techdata.TechDataServices;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;

import org.ofbiz.manufacturing.bom.BOMTree;

/**
 * Proposed Order Object generated by the MRP process or other re-Order process
 *
 */
public class ProposedOrder {

    public static final String module = ProposedOrder.class.getName();
    public static final String resource = "ManufacturingUiLabels";

    protected GenericValue product;
    protected boolean isBuilt;
    protected String productId;
    protected String facilityId;
    protected String manufacturingFacilityId;
    protected String mrpName;
    protected Timestamp requiredByDate;
    protected Timestamp requirementStartDate;
    protected BigDecimal quantity;


    public ProposedOrder(GenericValue product, String facilityId, String manufacturingFacilityId, boolean isBuilt, Timestamp requiredByDate, BigDecimal quantity) {
        this.product = product;
        this.productId = product.getString("productId");
        this.facilityId = facilityId;
        this.manufacturingFacilityId = manufacturingFacilityId;
        this.isBuilt = isBuilt;
        this.requiredByDate = requiredByDate;
        this.quantity = quantity;
        this.requirementStartDate = null;
    }
    /**
     * get the quantity property.
     * @return the quantity property
     **/
    public BigDecimal getQuantity() {
        return quantity;
    }
    /**
     * get the requirementStartDate property.
     * @return the quantity property
     **/
    public Timestamp getRequirementStartDate() {
        return requirementStartDate;
    }
    /**
     * calculate the ProposedOrder requirementStartDate and update the requirementStartDate property.
     * <li>For the build product, <ul>
     *         <li>read the routing associated to the product,
     *         <li>read the routingTask associated to the routing
     *         <li> step by step calculate from the endDate the startDate</ul>
     * <li>For the bought product, the first ProductFacility.daysToShip is used to calculated the startDate
     * @return <ul>
     * <li>if ProposedOrder.isBuild a Map with all the routingTaskId as keys and estimatedStartDate as value.
     * <li>else null.
     **/
    public Map calculateStartDate(int daysToShip, GenericValue routing, GenericDelegator delegator, LocalDispatcher dispatcher, GenericValue userLogin) {
        Map result = null;
        Timestamp endDate = (Timestamp)requiredByDate.clone();
        Timestamp startDate = endDate;
        long timeToShip = daysToShip * 8 * 60 * 60 * 1000;
        if (isBuilt) {
            List listRoutingTaskAssoc = null;
            if (routing == null) {
                try {
                    Map routingInMap = UtilMisc.toMap("productId", product.getString("productId"), "ignoreDefaultRouting", "Y", "userLogin", userLogin);
                    Map routingOutMap = dispatcher.runSync("getProductRouting", routingInMap);
                    routing = (GenericValue)routingOutMap.get("routing");
                    listRoutingTaskAssoc = (List)routingOutMap.get("tasks");
                    if (routing == null) {
                        // try to find a routing linked to the virtual product
                        BOMTree tree = null;
                        ArrayList components = new ArrayList();
                        try {
                            tree = new BOMTree(product.getString("productId"), "MANUF_COMPONENT", requiredByDate, BOMTree.EXPLOSION_SINGLE_LEVEL, delegator, dispatcher, userLogin);
                            tree.setRootQuantity(quantity);
                            tree.print(components, true);
                            if (components.size() > 0) components.remove(0);
                        } catch (Exception exc) {
                            Debug.logWarning(exc.getMessage(), module);
                            tree = null;
                        }
                        if (tree != null && tree.getRoot() != null && tree.getRoot().getProduct() != null) {
                            routingInMap = UtilMisc.toMap("productId", tree.getRoot().getProduct().getString("productId"), "userLogin", userLogin);
                            routingOutMap = dispatcher.runSync("getProductRouting", routingInMap);
                            routing = (GenericValue)routingOutMap.get("routing");
                        }
                    }
                } catch (GenericServiceException gse) {
                    Debug.logWarning(gse.getMessage(), module);
                }
            }
            if (routing != null) {
                result = new HashMap();
                //Looks for all the routingTask (ordered by inversed (begin from the end) sequence number)
                if (listRoutingTaskAssoc == null) {
                    try {
                        Map routingTasksInMap = UtilMisc.toMap("workEffortId", routing.getString("workEffortId"), "userLogin", userLogin);
                        Map routingTasksOutMap = dispatcher.runSync("getRoutingTaskAssocs", routingTasksInMap);
                        listRoutingTaskAssoc = (List)routingTasksOutMap.get("routingTaskAssocs");
                    } catch (GenericServiceException gse) {
                        Debug.logWarning(gse.getMessage(), module);
                    }
                }
            }
            if (listRoutingTaskAssoc != null) {
                for (int i = 1; i <= listRoutingTaskAssoc.size(); i++) {
                    GenericValue routingTaskAssoc = (GenericValue) listRoutingTaskAssoc.get(listRoutingTaskAssoc.size() - i);
                    if (EntityUtil.isValueActive(routingTaskAssoc, endDate)) {
                        GenericValue routingTask = null;
                        try {
                            routingTask = routingTaskAssoc.getRelatedOneCache("ToWorkEffort");
                        } catch (GenericEntityException e) {
                            Debug.logError(e.getMessage(),  module);
                        }
                        // Calculate the estimatedStartDate
                        long totalTime = ProductionRun.getEstimatedTaskTime(routingTask, quantity, dispatcher);
                        if (i == listRoutingTaskAssoc.size()) {
                            // add the daysToShip at the end of the routing
                            totalTime += timeToShip;
                        }
                        startDate = TechDataServices.addBackward(TechDataServices.getTechDataCalendar(routingTask),endDate, totalTime);
                        // record the routingTask with the startDate associated
                        result.put(routingTask.getString("workEffortId"),startDate);
                        endDate = startDate;
                        /*
                         * This is a work in progress
                        GenericValue routingTask = null;
                        try {
                            Map timeInMap = UtilMisc.toMap("taskId", routingTaskAssoc.getString("workEffortIdTo"), "quantity", Double.valueOf(quantity), "userLogin", userLogin);
                            Map timeOutMap = dispatcher.runSync("getEstimatedTaskTime", timeInMap);
                            routingTask = (GenericValue)timeOutMap.get("routing");
                        } catch (GenericServiceException gse) {
                            Debug.logError(gse.getMessage(),  module);
                        }
                        */
                    }
                }
            } else {
                // routing is null
                Debug.logError("No routing found for product = "+ product.getString("productId"), module);
            }
        } else {
            // the product is purchased
            // TODO: REVIEW this code
            try {
                GenericValue techDataCalendar = product.getDelegator().findByPrimaryKeyCache("TechDataCalendar", UtilMisc.toMap("calendarId", "SUPPLIER"));
                startDate = TechDataServices.addBackward(techDataCalendar, endDate, timeToShip);
            } catch (GenericEntityException e) {
                Debug.logError(e, "Error : reading SUPPLIER TechDataCalendar: " + e.getMessage(), module);
            }
        }
        requirementStartDate = startDate;
        return result;
    }


    /**
     * calculate the ProposedOrder quantity and update the quantity property.
     * Read the first ProductFacility.reorderQuantity and calculate the quantity : if (quantity < reorderQuantity) quantity = reorderQuantity;
     **/
    // FIXME: facilityId
    public void calculateQuantityToSupply(BigDecimal reorderQuantity, BigDecimal minimumStock, ListIterator  listIterIEP) {
        //      TODO : use a better algorithm using Order management cost et Product Stock cost to calculate the re-order quantity
        //                     the variable listIterIEP will be used for that
        if (quantity.compareTo(reorderQuantity) < 0) {
            quantity = reorderQuantity;
        }
        /*
        if (quantity < minimumStock) {
            quantity = minimumStock;
        }
         */
    }

    /**
     * create a ProposedOrder in the Requirement Entity calling the createRequirement service.
     * @param ctx The DispatchContext used to call service to create the Requirement Entity record.
     * @return String the requirementId
     **/
    public String create(DispatchContext ctx, GenericValue userLogin) {
        if ("WIP".equals(product.getString("productTypeId"))) {
            // No requirements for Work In Process products
            return null;
        }
        LocalDispatcher dispatcher = ctx.getDispatcher();
        GenericDelegator delegator = ctx.getDelegator();
        Map parameters = UtilMisc.toMap("userLogin", userLogin);
        if (isBuilt) {
            try {
                BOMTree tree = new BOMTree(productId, "MANUF_COMPONENT", null, BOMTree.EXPLOSION_MANUFACTURING, delegator, dispatcher, userLogin);
                tree.setRootQuantity(quantity);
                tree.print(new ArrayList());
                requirementStartDate = tree.getRoot().getStartDate(manufacturingFacilityId, requiredByDate, true);
            } catch (Exception e) {
                Debug.logError(e,"Error : computing the requirement start date. " + e.getMessage(), module);
            }
        }
        parameters.put("productId", productId);
        parameters.put("statusId", "REQ_PROPOSED");
        parameters.put("facilityId", (isBuilt? manufacturingFacilityId: facilityId));
        parameters.put("requiredByDate", requiredByDate);
        parameters.put("requirementStartDate", requirementStartDate);
        parameters.put("quantity", quantity);
        parameters.put("requirementTypeId", (isBuilt? "INTERNAL_REQUIREMENT" : "PRODUCT_REQUIREMENT"));
        if (mrpName != null) {
            parameters.put("description", "MRP_" + mrpName);
        } else {
            parameters.put("description", "Automatically generated by MRP");
        }
        try {
            Map result = dispatcher.runSync("createRequirement", parameters);
            return (String) result.get("requirementId");
        } catch (GenericServiceException e) {
            Debug.logError(e,"Error : createRequirement with parameters = "+parameters+"--"+e.getMessage(), module);
            return null;
        }
    }

    public void setMrpName(String mrpName) {
        this.mrpName = mrpName;
    }
}

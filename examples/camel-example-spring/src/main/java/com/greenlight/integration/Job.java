package com.greenlight.integration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;

import org.apache.camel.Exchange;
import org.apache.camel.model.RoutesDefinition;
import org.apache.camel.spring.SpringCamelContext;

public class Job {

    private Exchange exchange;
    private JobStatus status;

    public Job(Exchange exchange) {
        this.exchange = exchange;
        this.status = JobStatus.New;
    }

    public void update(HashMap<String, Object> result) {

        try {

            String xml = (String)result.get("xml");
            String newStatus = (String)result.get("state");

            if (status == JobStatus.New && newStatus.toLowerCase().equals("start")) {
                run(xml);
            } else if (status == JobStatus.Running && newStatus.toLowerCase().equals("stop")) {
                stop(xml);
            }

        } catch (Exception ex) {

        }

    }

    private void stop(String xml) {

    }

    private void run(String xml) {

        try {
            InputStream is = new ByteArrayInputStream(xml.getBytes());

            RoutesDefinition routes = exchange.getContext().loadRoutesDefinition(is);

            exchange.getContext().addRouteDefinitions(routes.getRoutes());
        
            SpringCamelContext scc = (SpringCamelContext) exchange.getContext();
            
            status = JobStatus.Running;
        } catch (Exception ex) {
            
            System.out.println(ex.getStackTrace());

        }

    }

}

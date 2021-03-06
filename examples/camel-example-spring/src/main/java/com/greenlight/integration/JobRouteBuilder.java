package com.greenlight.integration;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.json.JettisonMappedXmlDriver;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;

import net.sf.json.JSONObject;
import net.sf.json.xml.XMLSerializer;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.xmljson.XmlJsonDataFormat;
import org.apache.camel.model.RoutesDefinition;
import org.apache.camel.spring.Main;
import org.apache.camel.spring.SpringRouteBuilder;
import org.codehaus.jackson.map.ObjectMapper;


public class JobRouteBuilder extends SpringRouteBuilder {

    /**
     * Allow this route to be run as an application
     */
    public static void main(String[] args) throws Exception {
        new Main().run(args);
    }

    public void configure() {
        
        
        from("timer://foo?period=5000").process(new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("{}");
            }
        }).to("direct:mongo");

        from("direct:mongo").to("mongodb:meteor?database=meteor&collection=feeds&operation=findAll").filter()
            .method(FeedListener.class, "onFeedUpdated").to("mock:end");

    }

    public static class FeedListener {

        public static FeedListener Instance = new FeedListener();

        private HashMap<String, Job> jobs;

        public FeedListener() {
            jobs = new HashMap<String, Job>();
        }

        public boolean onFeedUpdated(Exchange exchange) {
            try {
                Message m = exchange.getIn();
                Object obj = m.getBody();

                if (obj instanceof ArrayList) {

                    ArrayList arr = (ArrayList)obj;

                    for (Object item : arr) {

                        String json = item.toString();

                        HashMap<String, Object> result = new ObjectMapper().readValue(json, HashMap.class);

                        XStream xStream = new XStream(new DomDriver());
                        xStream.alias("map", java.util.Map.class);

                        String id = (String)result.get("_id");

                        Job j = null;

                        if (!jobs.containsKey(id)) {
                            j = new Job(exchange);
                            jobs.put(id, j);
                        } else {
                            j = jobs.get(id);
                        }

                        j.update(result);

                    }
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }

            return true;
        }
    }
}

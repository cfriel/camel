package com.greenlight.integration;

import java.util.ArrayList;
import java.util.List;

import org.apache.xerces.impl.xs.XSImplementationImpl;
import org.apache.xerces.impl.xs.XSLoaderImpl;
import org.apache.xerces.xs.StringList;
import org.apache.xerces.xs.XSAnnotation;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSImplementation;
import org.apache.xerces.xs.XSLoader;
import org.apache.xerces.xs.XSModel;
import org.apache.xerces.xs.XSModelGroup;
import org.apache.xerces.xs.XSNamedMap;
import org.apache.xerces.xs.XSObjectList;
import org.apache.xerces.xs.XSParticle;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTerm;
import org.apache.xerces.xs.XSTypeDefinition;

import org.w3c.dom.DOMConfiguration;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;

public class SchemaParser {

    static void printAnnotations(final XSObjectList annotations) {
        if (annotations.getLength() == 0) {
            System.out.println(", no annotations");
        } else {
            System.out.println(", " + annotations.getLength() + " annotation(s)");

            for (int i = 0; i < annotations.getLength(); ++i) {

                System.out.println(((XSAnnotation)annotations.item(i)).getAnnotationString());
            }
        }
    }

    static void parseXMLSchema(final String schemaFileName) {

        XSImplementation xsImplementation = new XSImplementationImpl();

        XSLoader xsLoader = xsImplementation.createXSLoader(null); // or
        new XSLoaderImpl();

        DOMConfiguration config = xsLoader.getConfig();

        config.setParameter("http://apache.org/xml/features/generate-synthetic-annotations", Boolean.TRUE);

        XSModel xsModel = xsLoader.loadURI(schemaFileName);

        XSNamedMap elementMap = xsModel.getComponents(XSConstants.ELEMENT_DECLARATION);
        
        for(Object elem : elementMap.keySet())
        {

        XSElementDeclaration topElementDecl = (XSElementDeclaration)elementMap.get(elem);

        process(topElementDecl);
        }
    }

    private static void process(final XSElementDeclaration elementDecl) {
        if (elementDecl == null) {
            System.err.println("elementDecl is null.");

            return;
        }

        XSComplexTypeDefinition typeDef = (XSComplexTypeDefinition)elementDecl.getTypeDefinition();

        System.out.print("Complex element: " + elementDecl.getName());

        XSObjectList attributeUsesList = typeDef.getAttributeUses();

        if (attributeUsesList.getLength() == 0) {
            System.out.print(", no attributes");
        } else {
            for (int i = 0; i < attributeUsesList.getLength(); ++i) {
                System.out.println("Attribute " + i + 1 + ": " + attributeUsesList.item(i).getName());
            }
        }

        List<XSElementDeclaration> ces = new ArrayList<XSElementDeclaration>();

        if (typeDef.getParticle() != null) {

            XSModelGroup modelGroup = (XSModelGroup)typeDef.getParticle().getTerm();

            XSObjectList particles = modelGroup.getParticles();

            for (int i = 0; i < particles.getLength(); ++i) {
                XSParticle particle = (XSParticle)particles.item(i);
                XSTerm term = particle.getTerm();

                XSObjectList annotations = particle.getAnnotations();

                printAnnotations(annotations);

                if (term instanceof XSElementDeclaration) {
                    XSElementDeclaration newElement = (XSElementDeclaration)term;

                    if (newElement.getTypeDefinition().getTypeCategory() == XSTypeDefinition.SIMPLE_TYPE) {
                        System.out.print("Simple element: " + newElement.getName());

                        XSObjectList simpleElementAnnotations = newElement.getAnnotations();

                        printAnnotations(simpleElementAnnotations);
                    } else if (newElement.getTypeDefinition().getTypeCategory() == XSTypeDefinition.COMPLEX_TYPE) {
                        ces.add(newElement);
                    } else {
                        System.out.println("Other type.");
                    }
                } else {
                    System.out.println("Something else.");
                }
            }
        }

        for (XSElementDeclaration e : ces) {
            process(e);
        }

    }

    public static void main(String[] args) {

        parseXMLSchema("file:///data/greenlight/src/packages/greenlight-feeds/camel-spring-2.11.1.xsd");

        // Get DOM Implementation using DOM Registry
        // System.setProperty(DOMImplementationRegistry.PROPERTY,
        // "org.apache.xerces.dom.DOMXSImplementationSourceImpl");
        //
        // DOMImplementationRegistry registry;
        // try {
        // registry = DOMImplementationRegistry.newInstance();
        // XSImplementation impl =
        // (XSImplementation)registry.getDOMImplementation("XS-Loader");
        //
        // XSLoader schemaLoader = impl.createXSLoader(null);
        //
        // XSModel model = schemaLoader
        // .loadURI("file:///data/greenlight/src/packages/greenlight-feeds/camel-spring-2.11.1.xsd");
        //
        // XSNamedMap simpleDefinitions =
        // model.getComponents(XSTypeDefinition.SIMPLE_TYPE);
        //
        // for (Object key : simpleDefinitions.keySet()) {
        //
        // XSSimpleTypeDefinition obj =
        // (XSSimpleTypeDefinition)simpleDefinitions.get(key);
        //
        // StringList sl = obj.getLexicalEnumeration();
        // String name = obj.getName();
        // System.out.println(String.format("%s: %s", name, sl));
        //
        // }
        //
        // XSNamedMap complexDefinitions =
        // model.getComponents(XSTypeDefinition.COMPLEX_TYPE);
        //
        // for (Object key : complexDefinitions.keySet()) {
        //
        // XSComplexTypeDefinition typeDef =
        // (XSComplexTypeDefinition)complexDefinitions.get(key);
        // String name = typeDef.getName();
        //
        // if (typeDef.getParticle() != null) {
        // XSModelGroup modelGroup =
        // (XSModelGroup)typeDef.getParticle().getTerm();
        //
        // XSObjectList particles = modelGroup.getParticles();
        //
        // List<XSElementDeclaration> ces = new
        // ArrayList<XSElementDeclaration>();
        //
        // for (int i = 0; i < particles.getLength(); ++i) {
        // XSParticle particle = (XSParticle)particles.item(i);
        // XSTerm term = particle.getTerm();
        //
        // XSObjectList annotations = particle.getAnnotations();
        //
        // if (term instanceof XSElementDeclaration) {
        //
        // XSElementDeclaration newElement = (XSElementDeclaration)term;
        //
        // if (newElement.getTypeDefinition().getTypeCategory() ==
        // XSTypeDefinition.SIMPLE_TYPE) {
        // System.out.print("Simple element: " + newElement.getName());
        //
        // XSObjectList simpleElementAnnotations = newElement.getAnnotations();
        //
        // // printAnnotations(simpleElementAnnotations);
        // } else if (newElement.getTypeDefinition().getTypeCategory() ==
        // XSTypeDefinition.COMPLEX_TYPE) {
        // ces.add(newElement);
        // } else {
        // System.out.println("Other type.");
        // }
        // } else {
        // System.out.println("Something else.");
        // }
        // }
        //
        // for (XSElementDeclaration e : ces) {
        // // process(e);
        // }
        // }
        // }
        //
        // } catch (ClassCastException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // } catch (ClassNotFoundException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // } catch (InstantiationException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // } catch (IllegalAccessException e) {
        // // TODO Auto-generated catch block
        // e.printStackTrace();
        // }

    }

}

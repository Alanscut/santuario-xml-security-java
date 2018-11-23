/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.xml.security.test.dom.interop;

import java.io.File;
import java.io.FileInputStream;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Iterator;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.signature.Reference;
import org.apache.xml.security.signature.SignedInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.signature.reference.ReferenceData;
import org.apache.xml.security.signature.reference.ReferenceNodeSetData;
import org.apache.xml.security.signature.reference.ReferenceOctetStreamData;
import org.apache.xml.security.test.dom.DSNamespaceContext;
import org.apache.xml.security.test.dom.TestUtils;
import org.apache.xml.security.utils.XMLUtils;
import org.apache.xml.security.utils.resolver.ResourceResolverSpi;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import static org.junit.Assert.*;

public class InteropTestBase {

    static org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(InteropTestBase.class);

    /**
     * Method verifyHMAC
     *
     * @param filename
     * @param resolver
     * @param hmacKey
     *
     * @throws Exception
     */
    public boolean verifyHMAC(
        String filename, ResourceResolverSpi resolver, boolean followManifests, byte[] hmacKey
    ) throws Exception {
        File f = new File(filename);
        org.w3c.dom.Document doc = XMLUtils.read(new FileInputStream(f), false);

        XPathFactory xpf = XPathFactory.newInstance();
        XPath xpath = xpf.newXPath();
        xpath.setNamespaceContext(new DSNamespaceContext());

        String expression = "//ds:Signature[1]";
        Element sigElement =
            (Element) xpath.evaluate(expression, doc, XPathConstants.NODE);
        XMLSignature signature = new XMLSignature(sigElement, f.toURI().toURL().toString());

        if (resolver != null) {
            signature.addResourceResolver(resolver);
        }
        signature.setFollowNestedManifests(followManifests);

        byte[] keybytes = hmacKey;
        javax.crypto.SecretKey sk = signature.createSecretKey(keybytes);

        return signature.checkSignatureValue(sk);
    }

    public boolean verify(String filename, ResourceResolverSpi resolver, boolean followManifests)
        throws Exception {
        return verify(filename, resolver, followManifests, true);
    }

    public boolean verify(String filename, ResourceResolverSpi resolver,
                          boolean followManifests, boolean secureValidation)
        throws Exception {
        return verify(filename, resolver, null, followManifests, secureValidation);
    }

    public boolean verify(String filename, ResourceResolverSpi resolver, String systemId,
                          boolean followManifests, boolean secureValidation)
        throws Exception {
        org.w3c.dom.Document doc = TestUtils.read(filename, systemId, false);

        XPathFactory xpf = XPathFactory.newInstance();
        XPath xpath = xpf.newXPath();
        xpath.setNamespaceContext(new DSNamespaceContext());

        String expression = "//ds:Signature[1]";
        Element sigElement =
            (Element) xpath.evaluate(expression, doc, XPathConstants.NODE);
        File f = new File(filename);
        XMLSignature signature = new XMLSignature(sigElement, f.toURI().toURL().toString(), secureValidation);

        if (resolver != null) {
            signature.addResourceResolver(resolver);
        }
        signature.setFollowNestedManifests(followManifests);


        KeyInfo ki = signature.getKeyInfo();
        boolean result = false;
        if (ki != null) {
            X509Certificate cert = ki.getX509Certificate();

            if (cert != null) {
                result = signature.checkSignatureValue(cert);
            } else {
                PublicKey pk = ki.getPublicKey();

                if (pk != null) {
                    result = signature.checkSignatureValue(pk);
                } else {
                    throw new RuntimeException(
                    "Did not find a public key, so I can't check the signature");
                }
            }
            checkReferences(signature);
        } else {
            throw new RuntimeException("Did not find a KeyInfo");
        }
        if (!result) {
            for (int i = 0; i < signature.getSignedInfo().getLength(); i++) {
                boolean refVerify =
                    signature.getSignedInfo().getVerificationResult(i);

                if (refVerify) {
                    LOG.debug("Reference " + i + " was OK");
                } else {
                    // JavaUtils.writeBytesToFilename(filename + i + ".apache.txt", signature.getSignedInfo().item(i).getContentsAfterTransformation().getBytes());
                    LOG.debug("Reference " + i + " was not OK");
                }
            }
            checkReferences(signature);
            //throw new RuntimeException("Falle:"+sb.toString());
        }

        return result;
    }

    private void checkReferences(XMLSignature xmlSignature) throws Exception {
        SignedInfo signedInfo = xmlSignature.getSignedInfo();
        assertTrue(signedInfo.getLength() > 0);
        for (int i = 0; i < signedInfo.getLength(); i++) {
            Reference reference = signedInfo.item(i);
            assertNotNull(reference);
            ReferenceData referenceData = reference.getReferenceData();
            assertNotNull(referenceData);

            if (referenceData instanceof ReferenceNodeSetData) {
                Iterator<Node> iter = ((ReferenceNodeSetData)referenceData).iterator();
                assertTrue(iter.hasNext());
                boolean found = false;
                while (iter.hasNext()) {
                    Node n = iter.next();
                    if (n instanceof Element) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found);
            } else if (referenceData instanceof ReferenceOctetStreamData) {
                assertNotNull(((ReferenceOctetStreamData)referenceData).getOctetStream());
            }
        }
    }

}

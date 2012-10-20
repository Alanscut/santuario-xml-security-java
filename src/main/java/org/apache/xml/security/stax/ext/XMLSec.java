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
package org.apache.xml.security.stax.ext;

import java.net.URISyntaxException;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.RSAPrivateKey;

import javax.crypto.SecretKey;

import org.apache.xml.security.exceptions.XMLSecurityException;
import org.apache.xml.security.stax.config.Init;

/**
 * This is the central class of the streaming XML-security framework.<br/>
 * Instances of the inbound and outbound security streams can be retrieved
 * with this class.
 *
 * @author $Author: coheigea $
 * @version $Revision: 1354898 $ $Date: 2012-06-28 11:19:02 +0100 (Thu, 28 Jun 2012) $
 */
public class XMLSec {
    
    static {
        try {
            Init.init(XMLSec.class.getClassLoader().getResource("security-config.xml").toURI());
        } catch (XMLSecurityException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Creates and configures an outbound streaming security engine
     *
     * @param securityProperties The user-defined security configuration
     * @return A new OutboundXMLSec
     * @throws XMLSecurityException
     *          if the initialisation failed
     * @throws org.apache.xml.security.stax.ext.XMLSecurityConfigurationException
     *          if the configuration is invalid
     */
    public static OutboundXMLSec getOutboundXMLSec(XMLSecurityProperties securityProperties) throws XMLSecurityException {
        if (securityProperties == null) {
            throw new XMLSecurityConfigurationException("stax.missingSecurityProperties");
        }

        securityProperties = validateAndApplyDefaultsToOutboundSecurityProperties(securityProperties);
        return new OutboundXMLSec(securityProperties);
    }

    /**
     * Creates and configures an inbound streaming security engine
     *
     * @param securityProperties The user-defined security configuration
     * @return A new InboundWSSec
     * @throws XMLSecurityException
     *          if the initialisation failed
     * @throws org.apache.xml.security.stax.ext.XMLSecurityConfigurationException
     *          if the configuration is invalid
     */
    public static InboundXMLSec getInboundWSSec(XMLSecurityProperties securityProperties) throws XMLSecurityException {
        if (securityProperties == null) {
            throw new XMLSecurityConfigurationException("stax.missingSecurityProperties");
        }

        securityProperties = validateAndApplyDefaultsToInboundSecurityProperties(securityProperties);
        return new InboundXMLSec(securityProperties);
    }

    /**
     * Validates the user supplied configuration and applies default values as appropriate for the outbound security engine
     *
     * @param securityProperties The configuration to validate
     * @return The validated configuration
     * @throws org.apache.xml.security.stax.ext.XMLSecurityConfigurationException
     *          if the configuration is invalid
     */
    public static XMLSecurityProperties validateAndApplyDefaultsToOutboundSecurityProperties(XMLSecurityProperties securityProperties) throws XMLSecurityConfigurationException {
        if (securityProperties.getOutAction() == null) {
            throw new XMLSecurityConfigurationException("stax.noOutputAction");
        }

        for (int i = 0; i < securityProperties.getOutAction().length; i++) {
            XMLSecurityConstants.Action action = securityProperties.getOutAction()[i];
            if (action.equals(XMLSecurityConstants.SIGNATURE)) {
                if (securityProperties.getSignatureAlgorithm() == null) {
                    if (securityProperties.getSignatureKey() instanceof RSAPrivateKey) {
                        securityProperties.setSignatureAlgorithm("http://www.w3.org/2000/09/xmldsig#rsa-sha1");
                    } else if (securityProperties.getSignatureKey() instanceof DSAPrivateKey) {
                        securityProperties.setSignatureAlgorithm("http://www.w3.org/2000/09/xmldsig#dsa-sha1");
                    } else if (securityProperties.getSignatureKey() instanceof SecretKey) {
                        securityProperties.setSignatureAlgorithm("http://www.w3.org/2000/09/xmldsig#hmac-sha1");
                    }
                }
                if (securityProperties.getSignatureDigestAlgorithm() == null) {
                    securityProperties.setSignatureDigestAlgorithm("http://www.w3.org/2000/09/xmldsig#sha1");
                }
                if (securityProperties.getSignatureCanonicalizationAlgorithm() == null) {
                    securityProperties.setSignatureCanonicalizationAlgorithm(XMLSecurityConstants.NS_C14N_EXCL_OMIT_COMMENTS);
                }
                if (securityProperties.getSignatureKeyIdentifierType() == null) {
                    securityProperties.setSignatureKeyIdentifierType(XMLSecurityConstants.XMLKeyIdentifierType.X509_ISSUER_SERIAL);
                }
            } else if (action.equals(XMLSecurityConstants.ENCRYPT)) {
                if (securityProperties.getEncryptionKeyTransportAlgorithm() == null) {
                    //@see http://www.w3.org/TR/2002/REC-xmlenc-core-20021210/Overview.html#rsa-1_5 :
                    //"RSA-OAEP is RECOMMENDED for the transport of AES keys"
                    //@see http://www.w3.org/TR/2002/REC-xmlenc-core-20021210/Overview.html#rsa-oaep-mgf1p
                    securityProperties.setEncryptionKeyTransportAlgorithm("http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p");
                }
                if (securityProperties.getEncryptionSymAlgorithm() == null) {
                    securityProperties.setEncryptionSymAlgorithm("http://www.w3.org/2001/04/xmlenc#aes256-cbc");
                }
            }
        }
        return new XMLSecurityProperties(securityProperties);
    }

    /**
     * Validates the user supplied configuration and applies default values as appropriate for the inbound security engine
     *
     * @param securityProperties The configuration to validate
     * @return The validated configuration
     * @throws org.apache.xml.security.stax.ext.XMLSecurityConfigurationException
     *          if the configuration is invalid
     */
    public static XMLSecurityProperties validateAndApplyDefaultsToInboundSecurityProperties(XMLSecurityProperties securityProperties) throws XMLSecurityConfigurationException {
        return new XMLSecurityProperties(securityProperties);
    }
}

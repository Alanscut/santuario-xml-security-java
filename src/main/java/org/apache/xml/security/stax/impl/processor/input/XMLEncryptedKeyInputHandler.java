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
package org.apache.xml.security.stax.impl.processor.input;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xml.security.binding.xmldsig.DigestMethodType;
import org.apache.xml.security.binding.xmldsig.KeyInfoType;
import org.apache.xml.security.binding.xmlenc.EncryptedKeyType;
import org.apache.xml.security.binding.xmlenc11.MGFType;
import org.apache.xml.security.exceptions.XMLSecurityException;
import org.apache.xml.security.stax.config.JCEAlgorithmMapper;
import org.apache.xml.security.stax.ext.*;
import org.apache.xml.security.stax.ext.stax.XMLSecEvent;
import org.apache.xml.security.stax.impl.securityToken.AbstractInboundSecurityToken;
import org.apache.xml.security.stax.impl.securityToken.SecurityTokenFactory;
import org.apache.xml.security.stax.impl.util.IDGenerator;
import org.apache.xml.security.stax.securityEvent.EncryptedKeyTokenSecurityEvent;
import org.apache.xml.security.stax.securityEvent.TokenSecurityEvent;
import org.xmlsecurity.ns.configuration.AlgorithmType;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.JAXBElement;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.Deque;

/**
 * An input handler for the EncryptedKey XML Structure
 *
 * @author $Author: coheigea $
 * @version $Revision: 1360243 $ $Date: 2012-07-11 16:53:55 +0100 (Wed, 11 Jul 2012) $
 */
public class XMLEncryptedKeyInputHandler extends AbstractInputSecurityHeaderHandler {

    private static final transient Log logger = LogFactory.getLog(XMLEncryptedKeyInputHandler.class);

    @Override
    public void handle(final InputProcessorChain inputProcessorChain, final XMLSecurityProperties securityProperties,
                       final Deque<XMLSecEvent> eventQueue, final Integer index) throws XMLSecurityException {
        @SuppressWarnings("unchecked")
        final EncryptedKeyType encryptedKeyType =
                ((JAXBElement<EncryptedKeyType>) parseStructure(eventQueue, index, securityProperties)).getValue();

        final XMLSecEvent responsibleXMLSecStartXMLEvent = getResponsibleStartXMLEvent(eventQueue, index);

        handle(inputProcessorChain, encryptedKeyType, responsibleXMLSecStartXMLEvent, securityProperties);
    }

    public void handle(final InputProcessorChain inputProcessorChain,
                       final EncryptedKeyType encryptedKeyType,
                       final XMLSecEvent responsibleXMLSecStartXMLEvent,
                       final XMLSecurityProperties securityProperties) throws XMLSecurityException {

        if (encryptedKeyType.getEncryptionMethod() == null) {
            throw new XMLSecurityException("stax.encryption.noEncAlgo");
        }

        if (encryptedKeyType.getId() == null) {
            encryptedKeyType.setId(IDGenerator.generateID(null));
        }

        final SecurityContext securityContext = inputProcessorChain.getSecurityContext();

        final SecurityTokenProvider securityTokenProvider = new SecurityTokenProvider() {

            private AbstractInboundSecurityToken securityToken = null;

            @SuppressWarnings("unchecked")
            @Override
            public SecurityToken getSecurityToken() throws XMLSecurityException {

                if (this.securityToken != null) {
                    return this.securityToken;
                }

                this.securityToken = new AbstractInboundSecurityToken(
                        securityContext, encryptedKeyType.getId(), null) {

                    private byte[] decryptedKey = null;

                    @Override
                    public Key getKey(String algorithmURI, XMLSecurityConstants.KeyUsage keyUsage, String correlationID)
                            throws XMLSecurityException {
                        Key key = getSecretKey().get(algorithmURI);
                        if (key != null) {
                            return key;
                        }

                        String algoFamily = JCEAlgorithmMapper.getJCERequiredKeyFromURI(algorithmURI);
                        key = new SecretKeySpec(getSecret(this, correlationID, algorithmURI), algoFamily);
                        setSecretKey(algorithmURI, key);
                        return key;
                    }

                    @Override
                    public SecurityToken getKeyWrappingToken() throws XMLSecurityException {
                        return getWrappingSecurityToken(this);
                    }

                    @Override
                    public XMLSecurityConstants.TokenType getTokenType() {
                        return XMLSecurityConstants.EncryptedKeyToken;
                    }

                    private SecurityToken wrappingSecurityToken = null;

                    private SecurityToken getWrappingSecurityToken(SecurityToken wrappedSecurityToken)
                            throws XMLSecurityException {
                        if (wrappingSecurityToken != null) {
                            return this.wrappingSecurityToken;
                        }
                        KeyInfoType keyInfoType = encryptedKeyType.getKeyInfo();
                        this.wrappingSecurityToken = SecurityTokenFactory.getInstance().getSecurityToken(
                                keyInfoType,
                                SecurityToken.KeyInfoUsage.DECRYPTION,
                                securityProperties,
                                securityContext
                        );
                        this.wrappingSecurityToken.addWrappedToken(wrappedSecurityToken);
                        return this.wrappingSecurityToken;
                    }

                    private byte[] getSecret(SecurityToken wrappedSecurityToken, String correlationID,
                                             String symmetricAlgorithmURI) throws XMLSecurityException {

                        if (this.decryptedKey != null) {
                            return this.decryptedKey;
                        }

                        String algorithmURI = encryptedKeyType.getEncryptionMethod().getAlgorithm();
                        if (algorithmURI == null) {
                            throw new XMLSecurityException("stax.encryption.noEncAlgo");
                        }
                        AlgorithmType encAlgo = JCEAlgorithmMapper.getAlgorithmMapping(algorithmURI);
                        if (encAlgo == null) {
                            throw new XMLSecurityException("algorithms.NoSuchMap", algorithmURI);
                        }

                        final SecurityToken wrappingSecurityToken = getWrappingSecurityToken(wrappedSecurityToken);

                        Cipher cipher;
                        try {
                            XMLSecurityConstants.KeyUsage keyUsage;
                            if (wrappingSecurityToken.isAsymmetric()) {
                                keyUsage = XMLSecurityConstants.Asym_Key_Wrap;
                            } else {
                                keyUsage = XMLSecurityConstants.Sym_Key_Wrap;
                            }

                            if (encAlgo.getJCEProvider() == null) {
                                cipher = Cipher.getInstance(encAlgo.getJCEName());
                            } else {
                                cipher = Cipher.getInstance(encAlgo.getJCEName(), encAlgo.getJCEProvider());
                            }
                            if (XMLSecurityConstants.NS_XENC11_RSAOAEP.equals(algorithmURI) ||
                                    XMLSecurityConstants.NS_XENC_RSAOAEPMGF1P.equals(algorithmURI)) {

                                final DigestMethodType digestMethodType =
                                        XMLSecurityUtils.getQNameType(encryptedKeyType.getEncryptionMethod().getContent(), XMLSecurityConstants.TAG_dsig_DigestMethod);
                                String jceDigestAlgorithm = "SHA-1";
                                if (digestMethodType != null) {
                                    jceDigestAlgorithm = JCEAlgorithmMapper.translateURItoJCEID(digestMethodType.getAlgorithm());
                                }

                                PSource.PSpecified pSource = PSource.PSpecified.DEFAULT;
                                final byte[] oaepParams =
                                        XMLSecurityUtils.getQNameType(encryptedKeyType.getEncryptionMethod().getContent(), XMLSecurityConstants.TAG_xenc_OAEPparams);
                                if (oaepParams != null) {
                                    pSource = new PSource.PSpecified(oaepParams);
                                }

                                MGF1ParameterSpec mgfParameterSpec = new MGF1ParameterSpec("SHA-1");
                                final MGFType mgfType =
                                        XMLSecurityUtils.getQNameType(encryptedKeyType.getEncryptionMethod().getContent(), XMLSecurityConstants.TAG_xenc11_MGF);
                                if (mgfType != null) {
                                    String jceMGFAlgorithm = JCEAlgorithmMapper.translateURItoJCEID(mgfType.getAlgorithm());
                                    mgfParameterSpec = new MGF1ParameterSpec(jceMGFAlgorithm);
                                }
                                OAEPParameterSpec oaepParameterSpec = new OAEPParameterSpec(jceDigestAlgorithm, "MGF1", mgfParameterSpec, pSource);
                                cipher.init(Cipher.UNWRAP_MODE, wrappingSecurityToken.getSecretKey(algorithmURI, keyUsage, correlationID), oaepParameterSpec);
                            } else {
                                cipher.init(Cipher.UNWRAP_MODE, wrappingSecurityToken.getSecretKey(algorithmURI, keyUsage, correlationID));
                            }
                            if (encryptedKeyType.getCipherData() == null
                                    || encryptedKeyType.getCipherData().getCipherValue() == null) {
                                throw new XMLSecurityException("stax.encryption.noCipherValue");
                            }
                        } catch (NoSuchPaddingException e) {
                            throw new XMLSecurityException(e);
                        } catch (NoSuchAlgorithmException e) {
                            throw new XMLSecurityException(e);
                        } catch (InvalidAlgorithmParameterException e) {
                            throw new XMLSecurityException(e);
                        } catch (InvalidKeyException e) {
                            throw new XMLSecurityException(e);
                        } catch (NoSuchProviderException e) {
                            throw new XMLSecurityException(e);
                        }

                        try {
                            Key key = cipher.unwrap(encryptedKeyType.getCipherData().getCipherValue(),
                                    encAlgo.getJCEName(),
                                    Cipher.SECRET_KEY);
                            return this.decryptedKey = key.getEncoded();
                        } catch (IllegalStateException e) {
                            throw new XMLSecurityException(e);
                        } catch (Exception e) {
                            logger.warn("Unwrapping of the encrypted key failed with error: " + e.getMessage() + ". " +
                                    "Generating a faked one to mitigate timing attacks.");

                            int keyLength = JCEAlgorithmMapper.getKeyLengthFromURI(symmetricAlgorithmURI);
                            this.decryptedKey = new byte[keyLength / 8];
                            XMLSecurityConstants.secureRandom.nextBytes(this.decryptedKey);
                            return this.decryptedKey;
                        }
                    }
                };
                this.securityToken.setElementPath(responsibleXMLSecStartXMLEvent.getElementPath());
                this.securityToken.setXMLSecEvent(responsibleXMLSecStartXMLEvent);
                return this.securityToken;
            }

            @Override
            public String getId() {
                return encryptedKeyType.getId();
            }
        };

        //register the key token for decryption:
        securityContext.registerSecurityTokenProvider(encryptedKeyType.getId(), securityTokenProvider);

        //fire a tokenSecurityEvent
        TokenSecurityEvent tokenSecurityEvent = new EncryptedKeyTokenSecurityEvent();
        tokenSecurityEvent.setSecurityToken((SecurityToken) securityTokenProvider.getSecurityToken());
        tokenSecurityEvent.setCorrelationID(encryptedKeyType.getId());
        securityContext.registerSecurityEvent(tokenSecurityEvent);

        //if this EncryptedKey structure contains a reference list, delegate it to a subclass
        if (encryptedKeyType.getReferenceList() != null) {
            handleReferenceList(inputProcessorChain, encryptedKeyType, securityProperties);
        }
    }

    protected void handleReferenceList(final InputProcessorChain inputProcessorChain,
                                       final EncryptedKeyType encryptedKeyType,
                                       final XMLSecurityProperties securityProperties) throws XMLSecurityException {
        // do nothing
    }

    /*
    <xenc:EncryptedKey xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Id="EncKeyId-1483925398">
        <xenc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#rsa-1_5" />
        <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
            <wsse:SecurityTokenReference xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                <wsse:KeyIdentifier EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
                    ValueType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509SubjectKeyIdentifier">pHoiKNGY2YsLBKxwIV+jURt858M=</wsse:KeyIdentifier>
                </wsse:SecurityTokenReference>
        </ds:KeyInfo>
        <xenc:CipherData>
            <xenc:CipherValue>Khsa9SN3ALNXOgGDKOqihvfwGsXb9QN/q4Fpi9uuThgz+3D4oRSMkrGSPCqwG13vddvHywGAA/XNbWNT+5Xivz3lURCDCc2H/92YlXXo/crQNJnPlLrLZ81bGOzbNo7lnYQBLp/77K7b1bhldZAeV9ZfEW7DjbOMZ+k1dnDCu3A=</xenc:CipherValue>
        </xenc:CipherData>
        <xenc:ReferenceList>
            <xenc:DataReference URI="#EncDataId-1612925417" />
        </xenc:ReferenceList>
    </xenc:EncryptedKey>
     */
}

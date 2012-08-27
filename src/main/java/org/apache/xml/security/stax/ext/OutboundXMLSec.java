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

import java.io.OutputStream;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamWriter;

import org.apache.xml.security.stax.config.JCEAlgorithmMapper;
import org.apache.xml.security.stax.ext.stax.XMLSecEvent;
import org.apache.xml.security.stax.impl.DocumentContextImpl;
import org.apache.xml.security.stax.impl.OutputProcessorChainImpl;
import org.apache.xml.security.stax.impl.SecurityContextImpl;
import org.apache.xml.security.stax.impl.XMLSecurityStreamWriter;
import org.apache.xml.security.stax.impl.processor.output.FinalOutputProcessor;
import org.apache.xml.security.stax.impl.processor.output.XMLEncryptOutputProcessor;
import org.apache.xml.security.stax.impl.processor.output.XMLSignatureOutputProcessor;
import org.apache.xml.security.stax.impl.util.IDGenerator;

/**
 * Outbound Streaming-XML-Security
 * An instance of this class can be retrieved over the XMLSec class
 *
 * @author $Author: coheigea $
 * @version $Revision: 1355448 $ $Date: 2012-06-29 16:38:18 +0100 (Fri, 29 Jun 2012) $
 */
public class OutboundXMLSec {

    private final XMLSecurityProperties securityProperties;

    public OutboundXMLSec(XMLSecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /**
     * This method is the entry point for the incoming security-engine.
     * Hand over a outputStream and use the returned XMLStreamWriter for further processing
     *
     * @param outputStream The original outputStream
     * @return A new XMLStreamWriter which does transparently the security processing.
     * @throws XMLSecurityException thrown when a Security failure occurs
     */
    public XMLStreamWriter processOutMessage(OutputStream outputStream, String encoding) throws XMLSecurityException {
        return processOutMessage((Object)outputStream, encoding);
    }

    /**
     * This method is the entry point for the incoming security-engine.
     * Hand over the original XMLStreamWriter and use the returned one for further processing
     *
     * @param xmlStreamWriter The original xmlStreamWriter
     * @return A new XMLStreamWriter which does transparently the security processing.
     * @throws XMLSecurityException thrown when a Security failure occurs
     */
    public XMLStreamWriter processOutMessage(XMLStreamWriter xmlStreamWriter, String encoding) throws XMLSecurityException {
        return processOutMessage((Object)xmlStreamWriter, encoding);
    }

    private XMLStreamWriter processOutMessage(Object output, String encoding) throws XMLSecurityException {
        final SecurityContextImpl securityContextImpl = new SecurityContextImpl();
        final DocumentContextImpl documentContext = new DocumentContextImpl();
        documentContext.setEncoding(encoding);

        OutputProcessorChainImpl outputProcessorChain = new OutputProcessorChainImpl(securityContextImpl, documentContext);

        for (int i = 0; i < securityProperties.getOutAction().length; i++) {
            XMLSecurityConstants.Action action = securityProperties.getOutAction()[i];
            if (action.equals(XMLSecurityConstants.SIGNATURE)) {
                XMLSignatureOutputProcessor signatureOutputProcessor = new XMLSignatureOutputProcessor();
                initializeOutputProcessor(outputProcessorChain, signatureOutputProcessor, action);
                
                configureSignatureKeys(securityContextImpl);
                List<SecurePart> signatureParts = securityProperties.getSignatureSecureParts();
                for (int j = 0; j < signatureParts.size(); j++) {
                    SecurePart securePart = signatureParts.get(j);
                    if (securePart.getIdToSign() == null) {
                        outputProcessorChain.getSecurityContext().putAsMap(
                                XMLSecurityConstants.SIGNATURE_PARTS,
                                securePart.getName(),
                                securePart
                        );
                    } else {
                        outputProcessorChain.getSecurityContext().putAsMap(
                                XMLSecurityConstants.SIGNATURE_PARTS,
                                securePart.getIdToSign(),
                                securePart
                        );
                    }
                }
            } else if (action.equals(XMLSecurityConstants.ENCRYPT)) {
                XMLEncryptOutputProcessor encryptOutputProcessor = new XMLEncryptOutputProcessor();
                initializeOutputProcessor(outputProcessorChain, encryptOutputProcessor, action);
                
                configureEncryptionKeys(securityContextImpl);
                List<SecurePart> encryptionParts = securityProperties.getEncryptionSecureParts();
                for (int j = 0; j < encryptionParts.size(); j++) {
                    SecurePart securePart = encryptionParts.get(j);
                    if (securePart.getIdToSign() == null) {
                        outputProcessorChain.getSecurityContext().putAsMap(
                                XMLSecurityConstants.ENCRYPTION_PARTS,
                                securePart.getName(),
                                securePart
                        );
                    } else {
                        outputProcessorChain.getSecurityContext().putAsMap(
                                XMLSecurityConstants.ENCRYPTION_PARTS,
                                securePart.getIdToSign(),
                                securePart
                        );
                    }
                }
            }
        }
        if (output instanceof OutputStream) {
            final FinalOutputProcessor finalOutputProcessor = new FinalOutputProcessor((OutputStream) output, encoding);
            initializeOutputProcessor(outputProcessorChain, finalOutputProcessor, null);

        } else if (output instanceof XMLStreamWriter) {
            final FinalOutputProcessor finalOutputProcessor = new FinalOutputProcessor((XMLStreamWriter) output);
            initializeOutputProcessor(outputProcessorChain, finalOutputProcessor, null);

        } else {
            throw new IllegalArgumentException(output + " is not supported as output");
        }

        return new XMLSecurityStreamWriter(outputProcessorChain);
    }

    private void initializeOutputProcessor(OutputProcessorChainImpl outputProcessorChain, OutputProcessor outputProcessor, XMLSecurityConstants.Action action) throws XMLSecurityException {
        outputProcessor.setXMLSecurityProperties(securityProperties);
        outputProcessor.setAction(action);
        outputProcessor.init(outputProcessorChain);
    }
    
    private void configureSignatureKeys(final SecurityContextImpl securityContextImpl) throws XMLSecurityException {
        Key key = securityProperties.getSignatureKey();
        X509Certificate[] x509Certificates = securityProperties.getSignatureCerts();
        if (key instanceof PrivateKey && (x509Certificates == null || x509Certificates.length == 0)) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, "noUserCertsFound");
        }
        
        final SecurityToken securityToken = new XMLSecSecurityToken(key, x509Certificates);
        final String securityTokenid = IDGenerator.generateID("SIG");
        
        final SecurityTokenProvider securityTokenProvider = new SecurityTokenProvider() {

            @Override
            public SecurityToken getSecurityToken() throws XMLSecurityException {
                return securityToken;
            }

            @Override
            public String getId() {
                return securityTokenid;
            }
        };
        securityContextImpl.registerSecurityTokenProvider(securityTokenid, securityTokenProvider);
        
        securityContextImpl.put(XMLSecurityConstants.PROP_USE_THIS_TOKEN_ID_FOR_SIGNATURE, securityTokenid);
    }
    
    private void configureEncryptionKeys(final SecurityContextImpl securityContextImpl) throws XMLSecurityException {
        // Sort out transport keys / key wrapping keys first.
        Key transportKey = securityProperties.getEncryptionTransportKey();
        X509Certificate transportCert = securityProperties.getEncryptionUseThisCertificate();
        X509Certificate[] transportCerts = null;
        if (transportCert != null) {
            transportCerts = new X509Certificate[]{transportCert};
        }
        final SecurityToken transportSecurityToken = new XMLSecSecurityToken(transportKey, transportCerts);
        
        // Now sort out the session key
        Key key = securityProperties.getEncryptionKey();
        if (key == null) {
            if (transportCert == null && transportKey == null) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_ENCRYPTION, "encryptionKeyMissing");
            }
            // If none is configured then generate one
            String keyAlgorithm = 
                JCEAlgorithmMapper.getJCERequiredKeyFromURI(securityProperties.getEncryptionSymAlgorithm());
            KeyGenerator keyGen;
            try {
                keyGen = KeyGenerator.getInstance(keyAlgorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_ENCRYPTION, e);
            }
            //the sun JCE provider expects the real key size for 3DES (112 or 168 bit)
            //whereas bouncy castle expects the block size of 128 or 192 bits
            if (keyAlgorithm.contains("AES")) {
                int keyLength = 
                    JCEAlgorithmMapper.getKeyLengthFromURI(securityProperties.getEncryptionSymAlgorithm());
                keyGen.init(keyLength);
            }

            key = keyGen.generateKey();
        }
        
        final XMLSecSecurityToken securityToken = new XMLSecSecurityToken(key, null);
        securityToken.setKeyWrappingToken(transportSecurityToken);
        final String securityTokenid = IDGenerator.generateID(null);
        
        final SecurityTokenProvider securityTokenProvider = new SecurityTokenProvider() {

            @Override
            public SecurityToken getSecurityToken() throws XMLSecurityException {
                return securityToken;
            }

            @Override
            public String getId() {
                return securityTokenid;
            }
        };
        securityContextImpl.registerSecurityTokenProvider(securityTokenid, securityTokenProvider);
        
        securityContextImpl.put(XMLSecurityConstants.PROP_USE_THIS_TOKEN_ID_FOR_ENCRYPTION, securityTokenid);
    }
    
    private static class XMLSecSecurityToken implements SecurityToken {
        private Key key;
        private X509Certificate[] certs;
        private boolean asymmetric;
        private SecurityToken keyWrappingToken;
        
        public XMLSecSecurityToken(Key key, X509Certificate[] certs) {
            this.key = key;
            this.certs = certs;
            if (key instanceof PrivateKey || key instanceof PublicKey || certs != null) {
                asymmetric = true;
            }
        }

        public String getId() {
            return null;
        }

        public Object getProcessor() {
            return null;
        }

        public boolean isAsymmetric() {
            return asymmetric;
        }

        public Key getSecretKey(
            String algorithmURI, XMLSecurityConstants.KeyUsage keyUsage, String correlationID
        ) throws XMLSecurityException {
            if (key instanceof SecretKey || key instanceof PrivateKey) {
                return key;
            }
            return null;
        }

        public PublicKey getPublicKey(
            String algorithmURI, XMLSecurityConstants.KeyUsage keyUsage, String correlationID
        ) throws XMLSecurityException {
            if (key instanceof PublicKey) {
                return (PublicKey)key;
            } else if (certs != null && certs.length > 0) {
                return certs[0].getPublicKey();
            }
            return null;
        }

        public X509Certificate[] getX509Certificates() throws XMLSecurityException {
            return certs;
        }

        public void verify() throws XMLSecurityException {
        }
        
        public void setKeyWrappingToken(SecurityToken keyWrappingToken) {
            this.keyWrappingToken = keyWrappingToken;
        }

        public SecurityToken getKeyWrappingToken() {
            return keyWrappingToken;
        }

        public XMLSecurityConstants.TokenType getTokenType() {
            return null;
        }

        @Override
        public List<QName> getElementPath() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public XMLSecEvent getXMLSecEvent() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public List<SecurityToken> getWrappedTokens()
                throws XMLSecurityException {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void addWrappedToken(SecurityToken securityToken) {
            // TODO Auto-generated method stub
            
        }

        @Override
        public void addTokenUsage(TokenUsage tokenUsage)
                throws XMLSecurityException {
            // TODO Auto-generated method stub
            
        }

        @Override
        public List<TokenUsage> getTokenUsages() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void setElementPath(List<QName> elementPath) {
            // TODO Auto-generated method stub
            
        }

        @Override
        public void setXMLSecEvent(XMLSecEvent xmlSecEvent) {
            // TODO Auto-generated method stub
            
        }
    };
}

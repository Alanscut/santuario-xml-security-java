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

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xml.security.binding.excc14n.InclusiveNamespaces;
import org.apache.xml.security.binding.xmldsig.ReferenceType;
import org.apache.xml.security.binding.xmldsig.SignatureType;
import org.apache.xml.security.binding.xmldsig.TransformType;
import org.apache.xml.security.stax.config.ConfigurationProperties;
import org.apache.xml.security.stax.config.JCEAlgorithmMapper;
import org.apache.xml.security.stax.config.ResourceResolverMapper;
import org.apache.xml.security.stax.ext.*;
import org.apache.xml.security.stax.ext.stax.XMLSecEndElement;
import org.apache.xml.security.stax.ext.stax.XMLSecEvent;
import org.apache.xml.security.stax.ext.stax.XMLSecStartElement;
import org.apache.xml.security.stax.impl.transformer.canonicalizer.Canonicalizer20010315_OmitCommentsTransformer;
import org.apache.xml.security.stax.impl.util.DigestOutputStream;
import org.apache.xml.security.stax.impl.util.IDGenerator;
import org.apache.xml.security.stax.impl.util.UnsynchronizedBufferedOutputStream;
import org.apache.xml.security.stax.securityEvent.AlgorithmSuiteSecurityEvent;
import org.xmlsecurity.ns.configuration.AlgorithmType;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;

/**
 * @author $Author$
 * @version $Revision$ $Date$
 */
public abstract class AbstractSignatureReferenceVerifyInputProcessor extends AbstractInputProcessor {

    private static final transient Log logger = LogFactory.getLog(AbstractSignatureReferenceVerifyInputProcessor.class);

    protected static final Integer maximumAllowedReferencesPerManifest =
            Integer.valueOf(ConfigurationProperties.getProperty("MaximumAllowedReferencesPerManifest"));
    protected static final Integer maximumAllowedTransformsPerReference =
            Integer.valueOf(ConfigurationProperties.getProperty("MaximumAllowedTransformsPerReference"));
    protected static final Boolean doNotThrowExceptionForManifests =
            Boolean.valueOf(ConfigurationProperties.getProperty("DoNotThrowExceptionForManifests"));
    protected static final Boolean allowNotSameDocumentReferences =
            Boolean.valueOf(ConfigurationProperties.getProperty("AllowNotSameDocumentReferences"));

    private final SignatureType signatureType;
    private final SecurityToken securityToken;
    private final Map<ResourceResolver, ReferenceType> sameDocumentReferences;
    private final Map<ResourceResolver, ReferenceType> externalReferences;
    private final List<ReferenceType> processedReferences;

    public AbstractSignatureReferenceVerifyInputProcessor(
            InputProcessorChain inputProcessorChain,
            SignatureType signatureType, SecurityToken securityToken,
            XMLSecurityProperties securityProperties) throws XMLSecurityException {
        super(securityProperties);
        this.signatureType = signatureType;
        this.securityToken = securityToken;

        List<ReferenceType> referencesTypeList = signatureType.getSignedInfo().getReference();
        if (referencesTypeList.size() > maximumAllowedReferencesPerManifest) {
            throw new XMLSecurityException(
                    XMLSecurityException.ErrorCode.INVALID_SECURITY,
                    "secureProcessing.MaximumAllowedReferencesPerManifest",
                    referencesTypeList.size(),
                    maximumAllowedReferencesPerManifest);
        }
        sameDocumentReferences = new HashMap<ResourceResolver, ReferenceType>(referencesTypeList.size() + 1);
        externalReferences = new HashMap<ResourceResolver, ReferenceType>(referencesTypeList.size() + 1);
        processedReferences = new ArrayList<ReferenceType>(referencesTypeList.size());

        Iterator<ReferenceType> referenceTypeIterator = referencesTypeList.iterator();
        while (referenceTypeIterator.hasNext()) {
            ReferenceType referenceType = referenceTypeIterator.next();
            if (!doNotThrowExceptionForManifests && XMLSecurityConstants.NS_XMLDSIG_MANIFEST.equals(referenceType.getType())) {
                throw new XMLSecurityException(
                        XMLSecurityException.ErrorCode.INVALID_SECURITY,
                        "secureProcessing.DoNotThrowExceptionForManifests"
                );
            }
            if (referenceType.getURI() == null) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK);
            }
            if (referenceType.getId() == null) {
                referenceType.setId(IDGenerator.generateID(null));
            }
            ResourceResolver resourceResolver =
                    ResourceResolverMapper.getResourceResolver(
                            referenceType.getURI(), inputProcessorChain.getDocumentContext().getBaseURI());

            if (resourceResolver.isSameDocumentReference()) {
                sameDocumentReferences.put(resourceResolver, referenceType);
            } else {
                if (!allowNotSameDocumentReferences) {
                    throw new XMLSecurityException(
                            XMLSecurityException.ErrorCode.INVALID_SECURITY,
                            "secureProcessing.AllowNotSameDocumentReferences"
                    );
                }
                externalReferences.put(resourceResolver, referenceType);
            }
        }
    }

    public SignatureType getSignatureType() {
        return signatureType;
    }

    public List<ReferenceType> getProcessedReferences() {
        return processedReferences;
    }

    public SecurityToken getSecurityToken() {
        return securityToken;
    }

    @Override
    public XMLSecEvent processNextHeaderEvent(InputProcessorChain inputProcessorChain)
            throws XMLStreamException, XMLSecurityException {
        return inputProcessorChain.processHeaderEvent();
    }

    @Override
    public XMLSecEvent processNextEvent(InputProcessorChain inputProcessorChain)
            throws XMLStreamException, XMLSecurityException {

        XMLSecEvent xmlSecEvent = inputProcessorChain.processEvent();
        switch (xmlSecEvent.getEventType()) {
            case XMLStreamConstants.START_ELEMENT:
                XMLSecStartElement xmlSecStartElement = xmlSecEvent.asStartElement();
                List<ReferenceType> referenceTypes = resolvesResource(xmlSecStartElement);
                if (!referenceTypes.isEmpty()) {
                    for (int i = 0; i < referenceTypes.size(); i++) {
                        ReferenceType referenceType = referenceTypes.get(i);

                        if (processedReferences.contains(referenceType)) {
                            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, "duplicateId");
                        }
                        InternalSignatureReferenceVerifier internalSignatureReferenceVerifier =
                                getSignatureReferenceVerifier(getSecurityProperties(), inputProcessorChain,
                                        referenceType, xmlSecStartElement.getName());
                        if (!internalSignatureReferenceVerifier.isFinished()) {
                            internalSignatureReferenceVerifier.processEvent(xmlSecEvent, inputProcessorChain);
                            inputProcessorChain.addProcessor(internalSignatureReferenceVerifier);
                        }
                        processedReferences.add(referenceType);
                        inputProcessorChain.getDocumentContext().setIsInSignedContent(
                                inputProcessorChain.getProcessors().indexOf(internalSignatureReferenceVerifier),
                                internalSignatureReferenceVerifier);

                        // Fire a SecurityEvent
                        List<QName> elementPath = xmlSecStartElement.getElementPath();
                        processElementPath(elementPath, inputProcessorChain, xmlSecEvent, referenceType);
                    }
                }
                break;
        }
        return xmlSecEvent;
    }

    protected abstract void processElementPath(
            List<QName> elementPath, InputProcessorChain inputProcessorChain, XMLSecEvent xmlSecEvent,
            ReferenceType referenceType) throws XMLSecurityException;

    protected List<ReferenceType> resolvesResource(XMLSecStartElement xmlSecStartElement) {
        List<ReferenceType> referenceTypes = Collections.emptyList();

        Iterator<Map.Entry<ResourceResolver, ReferenceType>> resourceResolverIterator = sameDocumentReferences.entrySet().iterator();
        while (resourceResolverIterator.hasNext()) {
            Map.Entry<ResourceResolver, ReferenceType> entry = resourceResolverIterator.next();
            if (entry.getKey().matches(xmlSecStartElement)) {
                if (referenceTypes == Collections.<ReferenceType>emptyList()) {
                    referenceTypes = new ArrayList<ReferenceType>();
                }
                referenceTypes.add(entry.getValue());
            }
        }
        return referenceTypes;
    }

    @Override
    public void doFinal(InputProcessorChain inputProcessorChain) throws XMLStreamException, XMLSecurityException {
        if (externalReferences.size() > 0) {
            Iterator<Map.Entry<ResourceResolver, ReferenceType>> externalReferenceIterator = externalReferences.entrySet().iterator();
            while (externalReferenceIterator.hasNext()) {
                Map.Entry<ResourceResolver, ReferenceType> referenceTypeEntry = externalReferenceIterator.next();
                ResourceResolver resourceResolver = referenceTypeEntry.getKey();
                ReferenceType referenceType = referenceTypeEntry.getValue();

                verifyExternalReference(inputProcessorChain, resourceResolver, referenceType);
                processedReferences.add(referenceType);
            }

            externalReferenceIterator = externalReferences.entrySet().iterator();
            while (externalReferenceIterator.hasNext()) {
                Map.Entry<ResourceResolver, ReferenceType> referenceTypeEntry = externalReferenceIterator.next();
                if (!processedReferences.contains(referenceTypeEntry.getValue())) {
                    throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, "unprocessedSignatureReferences");
                }
            }
        }

        Iterator<Map.Entry<ResourceResolver, ReferenceType>> sameDocumentReferenceIterator = sameDocumentReferences.entrySet().iterator();
        while (sameDocumentReferenceIterator.hasNext()) {
            Map.Entry<ResourceResolver, ReferenceType> referenceTypeEntry = sameDocumentReferenceIterator.next();
            if (!processedReferences.contains(referenceTypeEntry.getValue())) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, "unprocessedSignatureReferences");
            }
        }
        inputProcessorChain.doFinal();
    }

    protected InternalSignatureReferenceVerifier getSignatureReferenceVerifier(
            XMLSecurityProperties securityProperties, InputProcessorChain inputProcessorChain,
            ReferenceType referenceType, QName startElement) throws XMLSecurityException {
        return new InternalSignatureReferenceVerifier(securityProperties, inputProcessorChain, referenceType, startElement);
    }

    private void verifyExternalReference(InputProcessorChain inputProcessorChain, ResourceResolver resourceResolver,
                                         ReferenceType referenceType) throws XMLSecurityException, XMLStreamException {

        DigestOutputStream digestOutputStream;
        OutputStream bufferedDigestOutputStream;
        Transformer transformer;

        InputStream inputStream = new BufferedInputStream(resourceResolver.getInputStreamFromExternalReference());
        try {
            digestOutputStream = createMessageDigestOutputStream(referenceType, inputProcessorChain.getSecurityContext());
            bufferedDigestOutputStream = new UnsynchronizedBufferedOutputStream(digestOutputStream);

            if (referenceType.getTransforms() != null) {
                transformer = buildTransformerChain(referenceType, bufferedDigestOutputStream, inputProcessorChain, null);
                transformer.transform(inputStream);
                bufferedDigestOutputStream.close();
            } else {
                IOUtils.copy(inputStream, bufferedDigestOutputStream);
                bufferedDigestOutputStream.close();
            }
        } catch (IOException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } catch (NoSuchMethodException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } catch (IllegalAccessException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } catch (NoSuchAlgorithmException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } catch (InstantiationException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } catch (NoSuchProviderException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } catch (InvocationTargetException e) {
            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                logger.warn("Could not close external resource input stream, ignored.");
            }
        }
        compareDigest(digestOutputStream.getDigestValue(), referenceType);
    }

    protected DigestOutputStream createMessageDigestOutputStream(ReferenceType referenceType, SecurityContext securityContext)
            throws XMLSecurityException, NoSuchAlgorithmException, NoSuchProviderException {
        AlgorithmType digestAlgorithm =
                JCEAlgorithmMapper.getAlgorithmMapping(referenceType.getDigestMethod().getAlgorithm());

        MessageDigest messageDigest;
        if (digestAlgorithm.getJCEProvider() != null) {
            messageDigest = MessageDigest.getInstance(digestAlgorithm.getJCEName(), digestAlgorithm.getJCEProvider());
        } else {
            messageDigest = MessageDigest.getInstance(digestAlgorithm.getJCEName());
        }

        AlgorithmSuiteSecurityEvent algorithmSuiteSecurityEvent = new AlgorithmSuiteSecurityEvent();
        algorithmSuiteSecurityEvent.setAlgorithmURI(digestAlgorithm.getURI());
        algorithmSuiteSecurityEvent.setKeyUsage(XMLSecurityConstants.Dig);
        algorithmSuiteSecurityEvent.setCorrelationID(referenceType.getId());
        securityContext.registerSecurityEvent(algorithmSuiteSecurityEvent);

        return new DigestOutputStream(messageDigest);
    }

    protected Transformer buildTransformerChain(ReferenceType referenceType, OutputStream outputStream,
                                                InputProcessorChain inputProcessorChain,
                                                InternalSignatureReferenceVerifier internalSignatureReferenceVerifier)
            throws XMLSecurityException, XMLStreamException, NoSuchMethodException, InstantiationException,
            IllegalAccessException, InvocationTargetException {

        if (referenceType.getTransforms() == null || referenceType.getTransforms().getTransform().isEmpty()) {
            // If no Transforms then just default to an Inclusive without comments transform
            Transformer transformer = new Canonicalizer20010315_OmitCommentsTransformer();
            transformer.setOutputStream(outputStream);
            //todo algoSecEvent??
            return transformer;
        }

        List<TransformType> transformTypeList = referenceType.getTransforms().getTransform();

        if (transformTypeList.size() == 1 &&
                XMLSecurityConstants.NS_XMLDSIG_ENVELOPED_SIGNATURE.equals(transformTypeList.get(0).getAlgorithm())) {
            TransformType transformType = new TransformType();
            transformType.setAlgorithm("http://www.w3.org/TR/2001/REC-xml-c14n-20010315");
            transformTypeList.add(transformType);
        }

        if (transformTypeList.size() > maximumAllowedTransformsPerReference) {
            throw new XMLSecurityException(
                    XMLSecurityException.ErrorCode.INVALID_SECURITY,
                    "secureProcessing.MaximumAllowedTransformsPerReference",
                    transformTypeList.size(),
                    maximumAllowedTransformsPerReference);
        }

        Transformer parentTransformer = null;
        for (int i = transformTypeList.size() - 1; i >= 0; i--) {
            TransformType transformType = transformTypeList.get(i);

            InclusiveNamespaces inclusiveNamespacesType =
                    XMLSecurityUtils.getQNameType(transformType.getContent(),
                            XMLSecurityConstants.TAG_c14nExcl_InclusiveNamespaces);
            List<String> inclusiveNamespaces = inclusiveNamespacesType != null
                    ? inclusiveNamespacesType.getPrefixList()
                    : null;
            String algorithm = transformType.getAlgorithm();

            AlgorithmSuiteSecurityEvent algorithmSuiteSecurityEvent = new AlgorithmSuiteSecurityEvent();
            algorithmSuiteSecurityEvent.setAlgorithmURI(algorithm);
            algorithmSuiteSecurityEvent.setKeyUsage(XMLSecurityConstants.C14n);
            algorithmSuiteSecurityEvent.setCorrelationID(referenceType.getId());
            inputProcessorChain.getSecurityContext().registerSecurityEvent(algorithmSuiteSecurityEvent);

            if (parentTransformer != null) {
                parentTransformer = XMLSecurityUtils.getTransformer(parentTransformer, inclusiveNamespaces, algorithm, XMLSecurityConstants.DIRECTION.IN);
            } else {
                parentTransformer =
                        XMLSecurityUtils.getTransformer(inclusiveNamespaces, outputStream, algorithm, XMLSecurityConstants.DIRECTION.IN);
            }
        }
        return parentTransformer;
    }

    private void compareDigest(byte[] calculatedDigest, ReferenceType referenceType) throws XMLSecurityException {
        if (logger.isDebugEnabled()) {
            logger.debug("Calculated Digest: " + new String(Base64.encodeBase64(calculatedDigest)));
            logger.debug("Stored Digest: " + new String(Base64.encodeBase64(referenceType.getDigestValue())));
        }

        if (!MessageDigest.isEqual(referenceType.getDigestValue(), calculatedDigest)) {
            throw new XMLSecurityException(
                    XMLSecurityException.ErrorCode.FAILED_CHECK,
                    "digestVerificationFailed", referenceType.getURI());
        }
    }

    public class InternalSignatureReferenceVerifier extends AbstractInputProcessor {
        private ReferenceType referenceType;
        private Transformer transformer;
        private DigestOutputStream digestOutputStream;
        private OutputStream bufferedDigestOutputStream;
        private QName startElement;
        private int elementCounter = 0;
        private boolean finished = false;

        public InternalSignatureReferenceVerifier(
                XMLSecurityProperties securityProperties, InputProcessorChain inputProcessorChain,
                ReferenceType referenceType, QName startElement) throws XMLSecurityException {

            super(securityProperties);
            this.setStartElement(startElement);
            this.setReferenceType(referenceType);
            try {
                this.digestOutputStream = createMessageDigestOutputStream(referenceType, inputProcessorChain.getSecurityContext());
                this.bufferedDigestOutputStream = new UnsynchronizedBufferedOutputStream(this.getDigestOutputStream());
                this.transformer = buildTransformerChain(referenceType, bufferedDigestOutputStream, inputProcessorChain);
            } catch (NoSuchMethodException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            } catch (IllegalAccessException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            } catch (NoSuchAlgorithmException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            } catch (InstantiationException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            } catch (XMLStreamException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            } catch (NoSuchProviderException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            } catch (InvocationTargetException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            }
        }

        public Transformer buildTransformerChain(ReferenceType referenceType, OutputStream outputStream, InputProcessorChain inputProcessorChain)
                throws XMLSecurityException, XMLStreamException, NoSuchMethodException, InstantiationException,
                IllegalAccessException, InvocationTargetException {
            return AbstractSignatureReferenceVerifyInputProcessor.this.buildTransformerChain(
                    referenceType, outputStream, inputProcessorChain, this);
        }

        @Override
        public XMLSecEvent processNextHeaderEvent(InputProcessorChain inputProcessorChain)
                throws XMLStreamException, XMLSecurityException {
            return inputProcessorChain.processHeaderEvent();
        }

        @Override
        public XMLSecEvent processNextEvent(InputProcessorChain inputProcessorChain)
                throws XMLStreamException, XMLSecurityException {
            XMLSecEvent xmlSecEvent = inputProcessorChain.processEvent();
            processEvent(xmlSecEvent, inputProcessorChain);
            return xmlSecEvent;
        }

        public void processEvent(XMLSecEvent xmlSecEvent, InputProcessorChain inputProcessorChain)
                throws XMLStreamException, XMLSecurityException {

            getTransformer().transform(xmlSecEvent);
            switch (xmlSecEvent.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    this.elementCounter++;
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    XMLSecEndElement xmlSecEndElement = xmlSecEvent.asEndElement();
                    this.elementCounter--;

                    if (this.elementCounter == 0 && xmlSecEndElement.getName().equals(getStartElement())) {
                        getTransformer().doFinal();
                        try {
                            getBufferedDigestOutputStream().close();
                        } catch (IOException e) {
                            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
                        }

                        compareDigest(this.getDigestOutputStream().getDigestValue(), getReferenceType());

                        inputProcessorChain.removeProcessor(this);
                        inputProcessorChain.getDocumentContext().unsetIsInSignedContent(this);
                        setFinished(true);
                    }
                    break;
            }
        }

        public boolean isFinished() {
            return finished;
        }

        public void setFinished(boolean finished) {
            this.finished = finished;
        }

        public ReferenceType getReferenceType() {
            return referenceType;
        }

        public void setReferenceType(ReferenceType referenceType) {
            this.referenceType = referenceType;
        }

        public Transformer getTransformer() {
            return transformer;
        }

        public void setTransformer(Transformer transformer) {
            this.transformer = transformer;
        }

        public DigestOutputStream getDigestOutputStream() {
            return digestOutputStream;
        }

        public void setDigestOutputStream(DigestOutputStream digestOutputStream) {
            this.digestOutputStream = digestOutputStream;
        }

        public OutputStream getBufferedDigestOutputStream() {
            return bufferedDigestOutputStream;
        }

        public void setBufferedDigestOutputStream(OutputStream bufferedDigestOutputStream) {
            this.bufferedDigestOutputStream = bufferedDigestOutputStream;
        }

        public QName getStartElement() {
            return startElement;
        }

        public void setStartElement(QName startElement) {
            this.startElement = startElement;
        }
    }
}

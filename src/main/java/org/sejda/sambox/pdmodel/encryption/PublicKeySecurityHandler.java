/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sejda.sambox.pdmodel.encryption;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Iterator;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSEnvelopedData;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.KeyTransRecipientId;
import org.bouncycastle.cms.RecipientId;
import org.bouncycastle.cms.RecipientInformation;
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.sejda.sambox.cos.COSArray;
import org.sejda.sambox.cos.COSString;
import org.sejda.sambox.pdmodel.PDDocument;

/**
 * This class implements the public key security handler described in the PDF specification.
 *
 * @see PublicKeyProtectionPolicy to see how to protect document with this security handler.
 * @author Benoit Guillon
 */
public final class PublicKeySecurityHandler extends SecurityHandler
{
    /** The filter name. */
    public static final String FILTER = "Adobe.PubSec";

    private PublicKeyProtectionPolicy policy = null;
    
    /**
     * Constructor.
     */
    public PublicKeySecurityHandler()
    {
    }

    /**
     * Constructor used for encryption.
     *
     * @param p The protection policy.
     */
    public PublicKeySecurityHandler(PublicKeyProtectionPolicy p)
    {
        policy = p;
        this.keyLength = policy.getEncryptionKeyLength();
    }

    /**
     * Prepares everything to decrypt the document.
     *
     * @param encryption encryption dictionary, can be retrieved via
     * {@link PDDocument#getEncryption()}
     * @param documentIDArray document id which is returned via
     * {@link org.apache.pdfbox.cos.COSDocument#getDocumentID()} (not used by
     * this handler)
     * @param decryptionMaterial Information used to decrypt the document.
     *
     * @throws IOException If there is an error accessing data. If verbose mode
     * is enabled, the exception message will provide more details why the
     * match wasn't successful.
     */
    @Override
    public void prepareForDecryption(PDEncryption encryption, COSArray documentIDArray,
            DecryptionMaterial decryptionMaterial)
            throws IOException
    {
        if (!(decryptionMaterial instanceof PublicKeyDecryptionMaterial))
        {
            throw new IOException(
                    "Provided decryption material is not compatible with the document");
        }

        setDecryptMetadata(encryption.isEncryptMetaData());
        if (encryption.getLength() != 0)
        {
            this.keyLength = encryption.getLength();
        }

        PublicKeyDecryptionMaterial material = (PublicKeyDecryptionMaterial) decryptionMaterial;

        try
        {
            boolean foundRecipient = false;

            // the decrypted content of the enveloped data that match
            // the certificate in the decryption material provided
            byte[] envelopedData = null;

            // the bytes of each recipient in the recipients array
            byte[][] recipientFieldsBytes = new byte[encryption.getRecipientsLength()][];

            int recipientFieldsLength = 0;
            int i = 0;
            StringBuilder extraInfo = new StringBuilder();
            for (; i < encryption.getRecipientsLength(); i++)
            {
                COSString recipientFieldString = encryption.getRecipientStringAt(i);
                byte[] recipientBytes = recipientFieldString.getBytes();
                CMSEnvelopedData data = new CMSEnvelopedData(recipientBytes);
                Iterator<?> recipCertificatesIt = data.getRecipientInfos().getRecipients().iterator();
                int j = 0;
                while (recipCertificatesIt.hasNext())
                {
                    RecipientInformation ri = (RecipientInformation) recipCertificatesIt.next();
                    // Impl: if a matching certificate was previously found it is an error,
                    // here we just don't care about it
                    X509Certificate certificate = material.getCertificate();
                    X509CertificateHolder materialCert = null;
                    if (null != certificate)
                    {
                        materialCert = new X509CertificateHolder(certificate.getEncoded());
                    }
                    RecipientId rid = ri.getRID();
                    if (rid.match(materialCert) && !foundRecipient)
                    {
                        foundRecipient = true;
                        PrivateKey privateKey = (PrivateKey) material.getPrivateKey();
                        envelopedData = ri.getContent(new JceKeyTransEnvelopedRecipient(privateKey));
                        break;
                    }
                    j++;
                    if (certificate != null)
                    {
                        extraInfo.append('\n');
                        extraInfo.append(j);
                        extraInfo.append(": ");
                        if (rid instanceof KeyTransRecipientId)
                        {
                            appendCertInfo(extraInfo, (KeyTransRecipientId) rid, certificate, materialCert);
                        }
                    }
                }
                recipientFieldsBytes[i] = recipientBytes;
                recipientFieldsLength += recipientBytes.length;
            }
            if (!foundRecipient || envelopedData == null)
            {
                throw new IOException("The certificate matches none of " + i
                        + " recipient entries" + extraInfo.toString());
            }
            if (envelopedData.length != 24)
            {
                throw new IOException("The enveloped data does not contain 24 bytes");
            }
            // now envelopedData contains:
            // - the 20 bytes seed
            // - the 4 bytes of permission for the current user

            byte[] accessBytes = new byte[4];
            System.arraycopy(envelopedData, 20, accessBytes, 0, 4);

            AccessPermission currentAccessPermission = new AccessPermission(accessBytes);
            currentAccessPermission.setReadOnly();
            setCurrentAccessPermission(currentAccessPermission);

            // what we will put in the SHA1 = the seed + each byte contained in the recipients array
            byte[] sha1Input = new byte[recipientFieldsLength + 20];

            // put the seed in the sha1 input
            System.arraycopy(envelopedData, 0, sha1Input, 0, 20);

            // put each bytes of the recipients array in the sha1 input
            int sha1InputOffset = 20;
            for (byte[] recipientFieldsByte : recipientFieldsBytes)
            {
                System.arraycopy(recipientFieldsByte, 0, sha1Input, sha1InputOffset,
                        recipientFieldsByte.length);
                sha1InputOffset += recipientFieldsByte.length;
            }

            MessageDigest md = MessageDigests.getSHA1();
            byte[] mdResult = md.digest(sha1Input);

            // we have the encryption key ...
            setEncryptionKey(new byte[this.keyLength / 8]);
            System.arraycopy(mdResult, 0, getEncryptionKey(), 0, this.keyLength / 8);
        }
        catch (CMSException e)
        {
            throw new IOException(e);
        }
        catch (KeyStoreException e)
        {
            throw new IOException(e);
        }
        catch (CertificateEncodingException e)
        {
            throw new IOException(e);
        }
    }

    private void appendCertInfo(StringBuilder extraInfo, KeyTransRecipientId ktRid, 
            X509Certificate certificate, X509CertificateHolder materialCert)
    {
        BigInteger ridSerialNumber = ktRid.getSerialNumber();
        if (ridSerialNumber != null)
        {
            String certSerial = "unknown";
            BigInteger certSerialNumber = certificate.getSerialNumber();
            if (certSerialNumber != null)
            {
                certSerial = certSerialNumber.toString(16);
            }
            extraInfo.append("serial-#: rid ");
            extraInfo.append(ridSerialNumber.toString(16));
            extraInfo.append(" vs. cert ");
            extraInfo.append(certSerial);
            extraInfo.append(" issuer: rid \'");
            extraInfo.append(ktRid.getIssuer());
            extraInfo.append("\' vs. cert \'");
            extraInfo.append(materialCert == null ? "null" : materialCert.getIssuer());
            extraInfo.append("\' ");
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasProtectionPolicy()
    {
        return policy != null;
    }
}

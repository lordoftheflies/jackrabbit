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
package org.apache.jackrabbit.spi2davex;

import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.Path;
import org.apache.jackrabbit.spi.QValue;
import org.apache.jackrabbit.spi.commons.conversion.NamePathResolver;
import org.apache.jackrabbit.spi.commons.value.ValueFactoryQImpl;
import org.apache.jackrabbit.spi.commons.value.AbstractQValue;
import org.apache.jackrabbit.util.TransientFileFactory;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.jcr.ItemResourceConstants;
import org.apache.jackrabbit.webdav.jcr.property.ValuesProperty;
import org.apache.jackrabbit.webdav.property.DavProperty;
import org.apache.jackrabbit.webdav.property.DefaultDavProperty;
import org.apache.jackrabbit.webdav.xml.DomUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Arrays;

/**
 * <code>ValueFactoryImpl</code>...
 */
class QValueFactoryImpl extends org.apache.jackrabbit.spi.commons.value.QValueFactoryImpl {

    /**
     * A dummy value for calling the constructor of AbstractQValue
     */
    private static final Object DUMMY_VALUE = new Serializable() {
        private static final long serialVersionUID = -5667366239976271493L;
    };

    private final ValueLoader loader;
    private final ValueFactory vf;

    public QValueFactoryImpl() {
        this(null, null);
    }

    QValueFactoryImpl(NamePathResolver resolver, ValueLoader loader) {
        this.loader = loader;
        vf = new ValueFactoryQImpl(this, resolver);
    }

    /**
     * Create a BINARY QValue with the given length and the given uri used
     * to retrieve the value.
     *
     * @param length Length of the binary value.
     * @param uri Uri from which the the binary value can be accessed.
     * @param index The index of the value within the values array.
     * @return a new BINARY QValue.
     */
    QValue create(long length, String uri, int index) {
        if (loader == null) {
            throw new IllegalStateException();
        }
        return new BinaryQValue(length, uri, index);
    }

    /**
     *
     * @param uri The Uri from which the type info can be retrieved.
     * @return the type of the property with the given <code>uri</code>.
     * @throws IOException If an error occurs.
     * @throws RepositoryException If an error occurs.
     */
    int retrieveType(String uri) throws IOException, RepositoryException {
        return loader.loadType(uri);
    }

    //--------------------------------------------------------< Inner Class >---

    /**
     * <code>BinaryQValue</code> represents a binary <code>Value</code> which is
     * backed by a resource or byte[]. Unlike <code>BinaryValue</code> it has no
     * state, i.e. the <code>getStream()</code> method always returns a fresh
     * <code>InputStream</code> instance.
     */
    private class BinaryQValue extends AbstractQValue implements ValueLoader.Target {

        private static final long serialVersionUID = 2736654000266713469L;

        /**
         * empty array
         */
        private final byte[] EMPTY_BYTE_ARRAY = new byte[0];

        /**
         * max size for keeping tmp data in memory
         */
        private static final int MAX_BUFFER_SIZE = 0x10000;

        /**
         * underlying file
         */
        private transient File file;

        /**
         * flag indicating if this instance represents a <i>temporary</i> value
         * whose dynamically allocated resources can be explicitly freed on
         * {@link #discard()}.
         */
        private transient boolean temp;

        /**
         * Buffer for small-sized data
         */
        private byte[] buffer;

        /**
         * URI to retrieve the value from
         */
        private String uri;
        private long length;
        private int index = -1;
        private boolean initialized = true;

        private BinaryQValue(long length, String uri, int index) {
            super(DUMMY_VALUE, PropertyType.BINARY);
            this.length = length;
            this.uri = uri;
            this.index = index;
            initialized = false;
        }

        /**
         * Creates a new <code>BinaryQValue</code> instance from an
         * <code>InputStream</code>. The contents of the stream is spooled
         * to a temporary file or to a byte buffer if its size is smaller than
         * {@link #MAX_BUFFER_SIZE}.
         * <p/>
         * The <code>temp</code> parameter governs whether dynamically allocated
         * resources will be freed explicitly on {@link #discard()}. Note that any
         * dynamically allocated resources (temp file/buffer) will be freed
         * implicitly once this instance has been gc'ed.
         *
         * @param in stream to be represented as a <code>BinaryQValue</code> instance
         * @param temp flag indicating whether this instance represents a
         *             <i>temporary</i> value whose resources can be explicitly freed
         *             on {@link #discard()}.
         * @throws IOException if an error occurs while reading from the stream or
         *                     writing to the temporary file
         */
        private void init(InputStream in, boolean temp) throws IOException {
            byte[] spoolBuffer = new byte[0x2000];
            int read;
            int len = 0;
            OutputStream out = null;
            File spoolFile = null;
            try {
                while ((read = in.read(spoolBuffer)) > 0) {
                    if (out != null) {
                        // spool to temp file
                        out.write(spoolBuffer, 0, read);
                        len += read;
                    } else if (len + read > BinaryQValue.MAX_BUFFER_SIZE) {
                        // threshold for keeping data in memory exceeded;
                        // create temp file and spool buffer contents
                        TransientFileFactory fileFactory = TransientFileFactory.getInstance();
                        spoolFile = fileFactory.createTransientFile("bin", null, null);
                        out = new FileOutputStream(spoolFile);
                        out.write(buffer, 0, len);
                        out.write(spoolBuffer, 0, read);
                        buffer = null;
                        len += read;
                    } else {
                        // reallocate new buffer and spool old buffer contents
                        if (buffer == null) {
                            buffer = EMPTY_BYTE_ARRAY;
                        }
                        byte[] newBuffer = new byte[len + read];
                        System.arraycopy(buffer, 0, newBuffer, 0, len);
                        System.arraycopy(spoolBuffer, 0, newBuffer, len, read);
                        buffer = newBuffer;
                        len += read;
                    }
                }
            } finally {
                in.close();
                if (out != null) {
                    out.close();
                }
            }

            if (spoolFile == null && buffer == null) {
                // input stream was empty -> initialize an empty binary value
                this.temp = false;
                buffer = EMPTY_BYTE_ARRAY;
            } else {
                // init vars
                file = spoolFile;
                this.temp = temp;
            }
            initialized = true;
        }

        //---------------------------------------------------------< QValue >---

        /**
         * Returns the length of this <code>BinaryQValue</code>.
         *
         * @return The length, in bytes, of this <code>BinaryQValue</code>,
         *         or -1L if the length can't be determined.
         * @see QValue#getLength()
         */
        public long getLength() {
            if (file != null) {
                // this instance is backed by a 'real' file
                if (file.exists()) {
                    return file.length();
                } else {
                    return -1;
                }
            } else if (buffer != null) {
                // this instance is backed by an in-memory buffer
                return buffer.length;
            } else {
                // value has not yet been read from the server.
                return length;
            }
        }

        /**
         * @see QValue#getStream()
         */
        public InputStream getStream() throws RepositoryException {
            // if the value has not yet been loaded -> retrieve it first in
            // order to make sure that either 'file' or 'buffer' is set.
            if (file == null && buffer == null) {
                try {
                    loadBinary();
                } catch (IOException e) {
                    throw new RepositoryException(e);
                }
            }

            // always return a 'fresh' stream
            if (file != null) {
                // this instance is backed by a 'real' file
                try {
                    return new FileInputStream(file);
                } catch (FileNotFoundException fnfe) {
                    throw new RepositoryException("file backing binary value not found",
                        fnfe);
                }
            } else {
                return new ByteArrayInputStream(buffer);
            }
        }

        /**
         * @see QValue#getName()
         */
        public Name getName() throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        /**
         * @see QValue#getPath()
         */
        public Path getPath() throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        /**
         * Frees temporarily allocated resources such as temporary file, buffer, etc.
         * If this <code>BinaryQValue</code> is backed by a persistent resource
         * calling this method will have no effect.
         * @see QValue#discard()
         */
        public void discard() {
            if (!temp) {
                // do nothing if this instance is not backed by temporarily
                // allocated resource/buffer
                return;
            }
            if (file != null) {
                // this instance is backed by a temp file
                file.delete();
            } else if (buffer != null) {
                // this instance is backed by an in-memory buffer
                buffer = EMPTY_BYTE_ARRAY;
            }
        }

        //-----------------------------------------------< java.lang.Object >---
        /**
         * Returns a string representation of this <code>BinaryQValue</code>
         * instance. The string representation of a resource backed value is
         * the path of the underlying resource. If this instance is backed by an
         * in-memory buffer the generic object string representation of the byte
         * array will be used instead.
         *
         * @return A string representation of this <code>BinaryQValue</code> instance.
         */
        public String toString() {
            if (file != null) {
                // this instance is backed by a 'real' file
                return file.toString();
            } else if (buffer != null) {
                // this instance is backed by an in-memory buffer
                return buffer.toString();
            } else {
                return super.toString();
            }
        }

        /**
         * {@inheritDoc}
         */
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof BinaryQValue) {
                BinaryQValue other = (BinaryQValue) obj;
                // for both the value has not been loaded yet
                if (!initialized) {
                    if (other.uri != null) {
                        return uri.equals(uri);
                    } else {
                        // need to load the binary value in order to be able
                        // to compare the 2 values.
                        try {
                            loadBinary();
                        } catch (RepositoryException e) {
                            return false;
                        } catch (IOException e) {
                            return false;
                        }
                    }
                }
                // both have been loaded
                return ((file == null ? other.file == null : file.equals(other.file))
                    && Arrays.equals(buffer, other.buffer));
            }
            return false;
        }

        /**
         * Returns zero to satisfy the Object equals/hashCode contract.
         * This class is mutable and not meant to be used as a hash key.
         *
         * @return always zero
         * @see Object#hashCode()
         */
        public int hashCode() {
            return 0;
        }

        //----------------------------------------------------------------------

        private synchronized void loadBinary() throws RepositoryException, IOException {
            if (uri == null) {
                throw new IllegalStateException();
            }
            loader.loadBinary(uri, index, this);
        }

        //-----------------------------< Serializable >-------------------------
        private void writeObject(ObjectOutputStream out)
                throws IOException {
            out.defaultWriteObject();
            // write hasFile marker
            out.writeBoolean(file != null);
            // then write file if necessary
            if (file != null) {
                byte[] buffer = new byte[4096];
                int bytes;
                InputStream stream = new FileInputStream(file);
                while ((bytes = stream.read(buffer)) >= 0) {
                    // Write a segment of the input stream
                    if (bytes > 0) {
                        // just to ensure that no 0 is written
                        out.writeInt(bytes);
                        out.write(buffer, 0, bytes);
                    }
                }
                // Write the end of stream marker
                out.writeInt(0);
                // close stream
                stream.close();
            }
        }

        private void readObject(ObjectInputStream in)
                throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            boolean hasFile = in.readBoolean();
            if (hasFile) {
                file = File.createTempFile("binary-qvalue", "bin");

                OutputStream out = new FileOutputStream(file);
                byte[] buffer = new byte[4096];
                for (int bytes = in.readInt(); bytes > 0; bytes = in.readInt()) {
                    if (buffer.length < bytes) {
                        buffer = new byte[bytes];
                    }
                    in.readFully(buffer, 0, bytes);
                    out.write(buffer, 0, bytes);
                }
                out.close();
            }
            // deserialized value is always temp
            temp = true;
        }

        //---------------------------------------------------------< Target >---
        public void setStream(InputStream in) throws IOException {
            if (index == -1) {
                init(in, true);
            } else {
                // TODO: improve. jcr-server sends XML for multivalued properties
                try {
                    DocumentBuilder db = DomUtil.BUILDER_FACTORY.newDocumentBuilder();
                    Document doc = db.parse(in);
                    Element prop = DomUtil.getChildElement(doc, ItemResourceConstants.JCR_VALUES.getName(), ItemResourceConstants.JCR_VALUES.getNamespace());
                    DavProperty p = DefaultDavProperty.createFromXml(prop);
                    ValuesProperty vp = new ValuesProperty(p, PropertyType.BINARY, vf);

                    Value[] jcrVs = vp.getJcrValues();
                    init(jcrVs[index].getStream(), true);
                } catch (RepositoryException e) {
                    throw new IOException(e.getMessage());
                }catch (DavException e) {
                    throw new IOException(e.getMessage());
                } catch (SAXException e) {
                    throw new IOException(e.getMessage());
                } catch (ParserConfigurationException e) {
                    throw new IOException(e.getMessage());
                }
            }
        }
    }
}
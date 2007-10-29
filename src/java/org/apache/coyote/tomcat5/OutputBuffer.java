

/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the "License").  You may not use this file except
 * in compliance with the License.
 *
 * You can obtain a copy of the license at
 * glassfish/bootstrap/legal/CDDLv1.0.txt or
 * https://glassfish.dev.java.net/public/CDDLv1.0.html.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * HEADER in each file and include the License file at
 * glassfish/bootstrap/legal/CDDLv1.0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your
 * own identifying information: Portions Copyright [yyyy]
 * [name of copyright owner]
 *
 * Copyright 2005 Sun Microsystems, Inc. All rights reserved.
 *
 * Portions Copyright Apache Software Foundation.
 */ 

package org.apache.coyote.tomcat5;


import java.io.IOException;
import java.io.Writer;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.C2BConverter;



/**
 * The buffer used by Tomcat response. This is a derivative of the Tomcat 3.3
 * OutputBuffer, with the removal of some of the state handling (which in 
 * Coyote is mostly the Processor's responsability).
 *
 * @author Costin Manolache
 * @author Remy Maucherat
 */
public class OutputBuffer extends Writer
    implements ByteChunk.ByteOutputChannel {


    private static com.sun.org.apache.commons.logging.Log log=
        com.sun.org.apache.commons.logging.LogFactory.getLog( OutputBuffer.class );

    // -------------------------------------------------------------- Constants


    public static final String DEFAULT_ENCODING = 
        org.apache.coyote.Constants.DEFAULT_CHARACTER_ENCODING;
    public static final int DEFAULT_BUFFER_SIZE = 8*1024;
    static final int debug = 0;


    // ----------------------------------------------------- Instance Variables


    /**
     * The byte buffer.
     */
    private ByteChunk bb;


    /**
     * State of the output buffer.
     */
    private int state = 0;
    private boolean initial = true;


    /**
     * Number of bytes written.
     */
    private int bytesWritten = 0;


    /**
     * Number of chars written.
     */
    private int charsWritten = 0;


    /**
     * Flag which indicates if the output buffer is closed.
     */
    private boolean closed = false;


    /**
     * Do a flush on the next operation.
     */
    private boolean doFlush = false;


    /**
     * Byte chunk used to output bytes.
     */
    private ByteChunk outputChunk = new ByteChunk();


    /**
     * Encoding to use.
     */
    private String enc;


    /**
     * Encoder is set.
     */
    private boolean gotEnc = false;


    /**
     * List of encoders.
     */
    protected HashMap encoders = new HashMap();


    /**
     * Current char to byte converter.
     */
    protected C2BConverter conv;


    /**
     * Associated Coyote response.
     */
    private Response coyoteResponse;


    // START GlassFish 896
    private CoyoteResponse coyoResponse;
    // END GlassFish 896


    /**
     * Suspended flag. All output bytes will be swallowed if this is true.
     */
    private boolean suspended = false;


    // ----------------------------------------------------------- Constructors


    /**
     * Default constructor. Allocate the buffer with the default buffer size.
     */
    public OutputBuffer() {

        this(DEFAULT_BUFFER_SIZE);

    }


    // START S1AS8 4861933
    public OutputBuffer(boolean chunkingDisabled) {
        this(DEFAULT_BUFFER_SIZE, chunkingDisabled);
    }
    // END S1AS8 4861933


    /**
     * Alternate constructor which allows specifying the initial buffer size.
     * 
     * @param size Buffer size to use
     */
    public OutputBuffer(int size) {
        // START S1AS8 4861933
        /*
        bb = new ByteChunk(size);
        bb.setLimit(size);
        bb.setByteOutputChannel(this);
        cb = new CharChunk(size);
        cb.setCharOutputChannel(this);
        cb.setLimit(size);
        */
        this(size, false);
        // END S1AS8 4861933
    }


    // START S1AS8 4861933
    public OutputBuffer(int size, boolean chunkingDisabled) {
        bb = new ByteChunk(size);
        if (!chunkingDisabled) {
            bb.setLimit(size);
        }
        bb.setByteOutputChannel(this);
    }
    // END S1AS8 4861933


    // ------------------------------------------------------------- Properties


    /**
     * Associated Coyote response.
     * 
     * @param coyoteResponse Associated Coyote response
     */
    public void setResponse(Response coyoteResponse) {
	this.coyoteResponse = coyoteResponse;
    }


    // START GlassFish 896
    void setCoyoteResponse(CoyoteResponse coyoResponse) {
	this.coyoResponse = coyoResponse;
    }        
    // END GlassFish 896


    /**
     * Get associated Coyote response.
     * 
     * @return the associated Coyote response
     */
    public Response getResponse() {
        return this.coyoteResponse;
    }


    /**
     * Is the response output suspended ?
     * 
     * @return suspended flag value
     */
    public boolean isSuspended() {
        return this.suspended;
    }


    /**
     * Set the suspended flag.
     * 
     * @param suspended New suspended flag value
     */
    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Recycle the output buffer.
     */
    public void recycle() {

	if (log.isDebugEnabled())
            log.debug("recycle()");

        initial = true;
        bytesWritten = 0;
        charsWritten = 0;

        bb.recycle(); 
        closed = false;
        suspended = false;

        if (conv!= null) {
            conv.recycle();
        }

        gotEnc = false;
        enc = null;
    }


    /**
     * Close the output buffer. This tries to calculate the response size if 
     * the response has not been committed yet.
     * 
     * @throws IOException An underlying IOException occurred
     */
    public void close()
        throws IOException {

        if (closed)
            return;
        if (suspended)
            return;

        if ((!coyoteResponse.isCommitted()) 
            && (coyoteResponse.getContentLength() == -1)) {
            // If this didn't cause a commit of the response, the final content
            // length can be calculated
            if (!coyoteResponse.isCommitted()) {
                coyoteResponse.setContentLength(bb.getLength());
            }
        }

        doFlush(false);
        closed = true;

        coyoteResponse.finish();

    }


    /**
     * Flush bytes or chars contained in the buffer.
     * 
     * @throws IOException An underlying IOException occurred
     */
    public void flush()
        throws IOException {
        doFlush(true);
    }


    /**
     * Flush bytes or chars contained in the buffer.
     * 
     * @throws IOException An underlying IOException occurred
     */
    protected void doFlush(boolean realFlush)
        throws IOException {

        if (suspended)
            return;

        doFlush = true;
        if (initial){
            // START GlassFish 896
            coyoResponse.addCookieIfNecessary();
            // END GlassFish 896
            coyoteResponse.sendHeaders();
            initial = false;
        }
        if (bb.getLength() > 0) {
            bb.flushBuffer();
        }
        doFlush = false;

        if (realFlush) {
            coyoteResponse.action(ActionCode.ACTION_CLIENT_FLUSH, 
                                  coyoteResponse);
            // If some exception occurred earlier, or if some IOE occurred
            // here, notify the servlet with an IOE
            if (coyoteResponse.isExceptionPresent()) {
                throw new ClientAbortException
                    (coyoteResponse.getErrorException());
            }
        }

    }


    // ------------------------------------------------- Bytes Handling Methods


    /** 
     * Sends the buffer data to the client output, checking the
     * state of Response and calling the right interceptors.
     * 
     * @param buf Byte buffer to be written to the response
     * @param off Offset
     * @param cnt Length
     * 
     * @throws IOException An underlying IOException occurred
     */
    public void realWriteBytes(byte buf[], int off, int cnt)
	throws IOException {

        if (log.isDebugEnabled())
            log.debug("realWrite(b, " + off + ", " + cnt + ") " + coyoteResponse);

        if (closed)
            return;
        if (coyoteResponse == null)
            return;

        // If we really have something to write
        if (cnt > 0) {
            // real write to the adapter
            outputChunk.setBytes(buf, off, cnt);
            try {
                coyoteResponse.doWrite(outputChunk);
            } catch (IOException e) {
                // An IOException on a write is almost always due to
                // the remote client aborting the request.  Wrap this
                // so that it can be handled better by the error dispatcher.
                throw new ClientAbortException(e);
            }
        }

    }


    public void write(byte b[], int off, int len) throws IOException {

        if (suspended)
            return;

        writeBytes(b, off, len);

    }


    private void writeBytes(byte b[], int off, int len) 
        throws IOException {

        if (closed)
            return;
        if (log.isDebugEnabled())
            log.debug("write(b,off,len)");

        bb.append(b, off, len);
        bytesWritten += len;

        // if called from within flush(), then immediately flush
        // remaining bytes
        if (doFlush) {
            bb.flushBuffer();
        }

    }


    // XXX Char or byte ?
    public void writeByte(int b)
        throws IOException {

        if (suspended)
            return;

        bb.append( (byte)b );
        bytesWritten++;

    }


    // ------------------------------------------------- Chars Handling Methods


    public void write(int c)
        throws IOException {

        if (suspended)
            return;

        checkConverter();
        conv.convert((char) c);
        conv.flushBuffer();
        charsWritten++;
    }


    public void write(char c[])
        throws IOException {

        if (suspended)
            return;

        write(c, 0, c.length);

    }


    public void write(char c[], int off, int len)
        throws IOException {

        if (suspended)
            return;

        checkConverter();
        conv.convert(c, off, len);
        conv.flushBuffer();
        charsWritten += len;
    }


    /**
     * Append a string to the buffer
     */
    public void write(String s, int off, int len)
        throws IOException {

        if (suspended)
            return;

        charsWritten += len;
        if (s==null)
            s="null";
        checkConverter();
        conv.convert(s, off, len);
        conv.flushBuffer();
    }


    public void write(String s)
        throws IOException {

        if (suspended)
            return;

        if (s == null)
            s = "null";
        checkConverter();
        conv.convert(s);
        conv.flushBuffer();
    } 


    public void setEncoding(String s) {
        enc = s;
    }

    public void checkConverter() 
        throws IOException {

        if (!gotEnc)
            setConverter();

    }


    protected void setConverter() 
        throws IOException {

        if (coyoteResponse != null)
            enc = coyoteResponse.getCharacterEncoding();

        if (log.isDebugEnabled())
            log.debug("Got encoding: " + enc);

        gotEnc = true;
        if (enc == null)
            enc = DEFAULT_ENCODING;
        conv = (C2BConverter) encoders.get(enc);
        if (conv == null) {
            if (System.getSecurityManager() != null){
                try{
                    conv = (C2BConverter)AccessController.doPrivileged(
                            new PrivilegedExceptionAction(){

                                public Object run() throws IOException{
                                    return new C2BConverter(bb, enc);
                                }

                            }
                    );              
                }catch(PrivilegedActionException ex){
                    Exception e = ex.getException();
                    if (e instanceof IOException)
                        throw (IOException)e; 
                    
                    if (log.isDebugEnabled())
                        log.debug("setConverter: " + ex.getMessage());
                }
            } else {
                conv = new C2BConverter(bb, enc);
            }
            encoders.put(enc, conv);

        }
    }

    
    // --------------------  BufferedOutputStream compatibility


    /**
     * Real write - this buffer will be sent to the client
     */
    public void flushBytes()
        throws IOException {

        if (log.isDebugEnabled())
            log.debug("flushBytes() " + bb.getLength());
        bb.flushBuffer();

    }


    public int getBytesWritten() {
        return bytesWritten;
    }


    public int getCharsWritten() {
        return charsWritten;
    }


    public int getContentWritten() {
        return bytesWritten + charsWritten;
    }


    /** 
     * True if this buffer hasn't been used ( since recycle() ) -
     * i.e. no chars or bytes have been added to the buffer.  
     */
    public boolean isNew() {
        return (bytesWritten == 0) && (charsWritten == 0);
    }


    public void setBufferSize(int size) {
        if (size > bb.getLimit()) {
            bb.setLimit(size);
        }
    }


    public void reset() {

        bb.recycle();
        bytesWritten = 0;
        charsWritten = 0;
        gotEnc = false;
        enc = null;
        initial = true;

    }


    public int getBufferSize() {
        return bb.getLimit();
    }

}

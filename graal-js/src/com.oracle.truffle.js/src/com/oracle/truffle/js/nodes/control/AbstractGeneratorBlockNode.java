/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.control;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.WriteNode;
import com.oracle.truffle.js.runtime.Errors;

public abstract class AbstractGeneratorBlockNode extends AbstractBlockNode {
    @Child protected JavaScriptNode readStateNode;
    @Child protected WriteNode writeStateNode;
    @CompilationFinal(dimensions = 1) protected final long[] resumableIndices;

    protected AbstractGeneratorBlockNode(JavaScriptNode[] statements, JavaScriptNode readStateNode, WriteNode writeStateNode, long[] suspendableIndices) {
        super(statements);
        this.readStateNode = readStateNode;
        this.writeStateNode = writeStateNode;
        this.resumableIndices = suspendableIndices;
    }

    protected final int getStateAndReset(VirtualFrame frame) {
        Object value = readStateNode.execute(frame);
        int index = (value instanceof Integer) ? (int) value : 0;
        assert index == 0 || canResumeAt(index) : index;
        writeStateNode.executeWrite(frame, 0);
        return index;
    }

    protected final void setState(VirtualFrame frame, int index) {
        if (canResumeAt(index)) {
            writeStateNode.executeWrite(frame, index);
        } else {
            assert false : index;
        }
    }

    protected final boolean canResumeAt(int index) {
        return ((resumableIndices[index >> 6] & (1L << index)) != 0);
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        int index = getStateAndReset(frame);
        assert index < getStatements().length;
        block.executeVoid(frame, index);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        int index = getStateAndReset(frame);
        assert index < getStatements().length;
        return block.executeGeneric(frame, index);
    }

    @Override
    public void executeVoid(VirtualFrame frame, JavaScriptNode node, int index, int startIndex) {
        if (index < startIndex) {
            return;
        }
        try {
            node.executeVoid(frame);
        } catch (YieldException e) {
            setState(frame, index);
            throw e;
        }
    }

    @Override
    public Object executeGeneric(VirtualFrame frame, JavaScriptNode node, int index, int startIndex) {
        assert index == getStatements().length - 1;
        try {
            return node.execute(frame);
        } catch (YieldException e) {
            setState(frame, index);
            throw e;
        }
    }

    @Override
    public final AbstractBlockNode toGeneratorNode(JavaScriptNode readState, WriteNode writeState, long[] suspendableIndices) {
        throw Errors.unsupported("toGeneratorNode");
    }
}

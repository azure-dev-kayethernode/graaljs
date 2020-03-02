/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.nodes.binary;

import static com.oracle.truffle.js.nodes.JSGuards.isString;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.Truncatable;
import com.oracle.truffle.js.nodes.access.JSConstantNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantDoubleNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantIntegerNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantNumericUnitNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode.JSConstantStringNode;
import com.oracle.truffle.js.nodes.cast.JSDoubleToStringNode;
import com.oracle.truffle.js.nodes.cast.JSToNumericNode;
import com.oracle.truffle.js.nodes.cast.JSToPrimitiveNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.SafeInteger;
import com.oracle.truffle.js.runtime.objects.JSLazyString;

@NodeInfo(shortName = "+")
@ReportPolymorphism
public abstract class JSAddNode extends JSBinaryNode implements Truncatable {

    @CompilationFinal boolean truncate;

    protected JSAddNode(boolean truncate, JavaScriptNode left, JavaScriptNode right) {
        super(left, right);
        this.truncate = truncate;
    }

    public static JavaScriptNode create(JavaScriptNode left, JavaScriptNode right, boolean truncate) {
        if (JSConfig.UseSuperOperations) {
            if (left instanceof JSConstantIntegerNode) {
                if (right instanceof JSConstantIntegerNode) {
                    int leftValue = ((JSConstantIntegerNode) left).executeInt(null);
                    int rightValue = ((JSConstantIntegerNode) right).executeInt(null);
                    long value = (long) leftValue + (long) rightValue;
                    return JSRuntime.longIsRepresentableAsInt(value) ? JSConstantNode.createInt((int) value) : JSConstantNode.createDouble(value);
                }
                // can't swap operands here, could be string concat
            } else if (right instanceof JSConstantIntegerNode || right instanceof JSConstantDoubleNode) {
                Object rightValue = ((JSConstantNode) right).execute(null);
                return JSAddConstantRightNumberNodeGen.create(left, (Number) rightValue, truncate);
            } else if (left instanceof JSConstantStringNode && right instanceof JSConstantStringNode) {
                return JSConstantNode.createString((String) left.execute(null) + (String) right.execute(null));
            } else if (left instanceof JSConstantIntegerNode || left instanceof JSConstantDoubleNode) {
                Object leftValue = ((JSConstantNode) left).execute(null);
                return JSAddConstantLeftNumberNodeGen.create((Number) leftValue, right, truncate);
            }
        }
        if (right instanceof JSConstantNumericUnitNode) {
            return JSAddSubNumericUnitNode.create(left, true, truncate);
        }
        return JSAddNodeGen.create(truncate, left, right);
    }

    public static JavaScriptNode create(JavaScriptNode left, JavaScriptNode right) {
        return create(left, right, false);
    }

    public static JavaScriptNode createUnoptimized(JavaScriptNode left, JavaScriptNode right, boolean truncate) {
        return JSAddNodeGen.create(truncate, left, right);
    }

    public abstract Object execute(Object a, Object b);

    @Specialization(guards = "truncate")
    protected static int doIntTruncate(int a, int b) {
        return a + b;
    }

    @Specialization(guards = "!truncate", rewriteOn = ArithmeticException.class)
    protected static int doInt(int a, int b) {
        return Math.addExact(a, b);
    }

    @Specialization(guards = "!truncate", rewriteOn = ArithmeticException.class)
    protected static Object doIntOverflow(int a, int b) {
        long result = (long) a + (long) b;
        return doIntOverflowStaticLong(result);
    }

    static Object doIntOverflowStaticLong(long result) {
        if (JSRuntime.longIsRepresentableAsInt(result)) {
            return (int) result;
        } else if (JSRuntime.isSafeInteger(result)) {
            return SafeInteger.valueOf(result);
        } else {
            throw new ArithmeticException();
        }
    }

    @Specialization(guards = "truncate")
    protected static int doIntSafeIntegerTruncate(int a, SafeInteger b) {
        return (int) (a + b.longValue());
    }

    @Specialization(guards = "truncate")
    protected static int doSafeIntegerIntTruncate(SafeInteger a, int b) {
        return (int) (a.longValue() + b);
    }

    @Specialization(guards = "truncate")
    protected static int doSafeIntegerTruncate(SafeInteger a, SafeInteger b) {
        return (int) (a.longValue() + b.longValue());
    }

    @Specialization(guards = "!truncate", rewriteOn = ArithmeticException.class)
    protected static SafeInteger doIntSafeInteger(int a, SafeInteger b) {
        return SafeInteger.valueOf(a).addExact(b);
    }

    @Specialization(guards = "!truncate", rewriteOn = ArithmeticException.class)
    protected static SafeInteger doSafeIntegerInt(SafeInteger a, int b) {
        return a.addExact(SafeInteger.valueOf(b));
    }

    @Specialization(guards = "!truncate", rewriteOn = ArithmeticException.class)
    protected static SafeInteger doSafeInteger(SafeInteger a, SafeInteger b) {
        return a.addExact(b);
    }

    @Specialization
    protected static double doDouble(double a, double b) {
        return a + b;
    }

    @Specialization
    protected BigInt doBigInt(BigInt left, BigInt right) {
        return left.add(right);
    }

    @Specialization
    protected CharSequence doString(CharSequence a, CharSequence b,
                    @Cached @Shared("concatStringsNode") JSConcatStringsNode concatStringsNode) {
        return concatStringsNode.executeCharSequence(a, b);
    }

    @Specialization
    protected CharSequence doStringInt(CharSequence a, int b) {
        return JSLazyString.createLazyInt(a, b);
    }

    @Specialization
    protected CharSequence doIntString(int a, CharSequence b) {
        return JSLazyString.createLazyInt(a, b);
    }

    @Specialization(guards = "isNumber(b)")
    protected CharSequence doStringNumber(CharSequence a, Object b,
                    @Cached @Shared("concatStringsNode") JSConcatStringsNode concatStringsNode,
                    @Cached @Shared("doubleToStringNode") JSDoubleToStringNode doubleToStringNode) {
        return concatStringsNode.executeCharSequence(a, doubleToStringNode.executeString(b));
    }

    @Specialization(guards = "isNumber(a)")
    protected CharSequence doNumberString(Object a, CharSequence b,
                    @Cached @Shared("concatStringsNode") JSConcatStringsNode concatStringsNode,
                    @Cached @Shared("doubleToStringNode") JSDoubleToStringNode doubleToStringNode) {
        return concatStringsNode.executeCharSequence(doubleToStringNode.executeString(a), b);
    }

    @Specialization(replaces = {"doInt", "doIntOverflow", "doIntTruncate", "doSafeInteger", "doIntSafeInteger", "doSafeIntegerInt", "doSafeIntegerTruncate", "doIntSafeIntegerTruncate",
                    "doSafeIntegerIntTruncate", "doDouble", "doBigInt", "doString", "doStringInt", "doIntString", "doStringNumber", "doNumberString"})
    protected Object doPrimitiveConversion(Object a, Object b,
                    @Cached("createHintNone()") JSToPrimitiveNode toPrimitiveA,
                    @Cached("createHintNone()") JSToPrimitiveNode toPrimitiveB,
                    @Cached("create()") JSToNumericNode toNumericA,
                    @Cached("create()") JSToNumericNode toNumericB,
                    @Cached("create()") JSToStringNode toStringA,
                    @Cached("create()") JSToStringNode toStringB,
                    @Cached("createBinaryProfile()") ConditionProfile profileA,
                    @Cached("createBinaryProfile()") ConditionProfile profileB,
                    @Cached("copyRecursive()") JSAddNode add,
                    @Cached("create()") BranchProfile mixedNumericTypes) {

        Object primitiveA = toPrimitiveA.execute(a);
        Object primitiveB = toPrimitiveB.execute(b);

        Object castA;
        Object castB;
        if (profileA.profile(isString(primitiveA))) {
            castA = primitiveA;
            castB = toStringB.executeString(primitiveB);
        } else if (profileB.profile(isString(primitiveB))) {
            castA = toStringA.executeString(primitiveA);
            castB = primitiveB;
        } else {
            castA = toNumericA.execute(primitiveA);
            castB = toNumericB.execute(primitiveB);
            ensureBothSameNumericType(castA, castB, mixedNumericTypes);
        }
        return add.execute(castA, castB);
    }

    public final JSAddNode copyRecursive() {
        return (JSAddNode) create(null, null, truncate);
    }

    @Override
    public void setTruncate() {
        CompilerAsserts.neverPartOfCompilation();
        if (truncate == false) {
            truncate = true;
        }
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return JSAddNodeGen.createUnoptimized(cloneUninitialized(getLeft()), cloneUninitialized(getRight()), truncate);
    }
}

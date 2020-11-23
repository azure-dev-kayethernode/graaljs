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
package com.oracle.truffle.js.nodes.interop;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.SafeInteger;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionObject;
import com.oracle.truffle.js.runtime.interop.InteropAsyncFunction;
import com.oracle.truffle.js.runtime.interop.InteropBoundFunction;
import com.oracle.truffle.js.runtime.objects.JSLazyString;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * This node prepares the export of a value via Interop. It transforms values not allowed in Truffle
 * (e.g. {@link JSLazyString}) and binds Functions. See also {@link JSRuntime#exportValue(Object)}.
 *
 * @see JSRuntime#exportValue(Object)
 */
@ImportStatic({JSGuards.class, JSFunction.class})
@GenerateUncached
public abstract class ExportValueNode extends JavaScriptBaseNode {

    ExportValueNode() {
    }

    public final Object execute(Object value) {
        return execute(value, Undefined.instance, false);
    }

    public abstract Object execute(Object value, Object thiz, boolean bindMemberFunctions);

    public static boolean isInteropCompletePromises(JavaScriptLanguage lang) {
        return lang.getJSContext().getContextOptions().interopCompletePromises();
    }

    @Specialization(guards = {"!bindFunctions", "!isInteropCompletePromises(language) || !isAsyncFunction(function)"})
    protected static DynamicObject doFunctionNoBind(JSFunctionObject function, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions,
                    @CachedLanguage @SuppressWarnings("unused") JavaScriptLanguage language) {
        return function;
    }

    @Specialization(guards = {"bindFunctions", "isUndefined(thiz)", "!isInteropCompletePromises(language) || !isAsyncFunction(function)"})
    protected static DynamicObject doFunctionUndefinedThis(JSFunctionObject function, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions,
                    @CachedLanguage @SuppressWarnings("unused") JavaScriptLanguage language) {
        return function;
    }

    @Specialization(guards = {"bindFunctions", "!isUndefined(thiz)", "!isBoundJSFunction(function)", "!isInteropCompletePromises(language) || !isAsyncFunction(function)"})
    protected static TruffleObject doBindUnboundFunction(JSFunctionObject function, Object thiz, @SuppressWarnings("unused") boolean bindFunctions,
                    @CachedLanguage @SuppressWarnings("unused") JavaScriptLanguage language) {
        return new InteropBoundFunction(function, thiz);
    }

    @Specialization(guards = {"bindFunctions", "isBoundJSFunction(function)", "!isInteropCompletePromises(language) || !isAsyncFunction(function)"})
    protected static DynamicObject doBoundFunction(JSFunctionObject function, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions,
                    @CachedLanguage @SuppressWarnings("unused") JavaScriptLanguage language) {
        return function;
    }

    @Specialization(guards = {"isInteropCompletePromises(language)", "isAsyncFunction(function)"})
    protected static TruffleObject doAsyncFunction(JSFunctionObject function, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions,
                    @CachedLanguage @SuppressWarnings("unused") JavaScriptLanguage language) {
        return new InteropAsyncFunction(function);
    }

    @Specialization
    protected static double doSafeInteger(SafeInteger value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value.doubleValue();
    }

    @Specialization(guards = {"!isJSFunction(value)"})
    protected static DynamicObject doObject(DynamicObject value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization
    protected static int doInt(int value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization
    protected static long doLong(long value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization
    protected static double doDouble(double value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization
    protected static boolean doBoolean(boolean value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization
    protected static BigInt doBigInt(BigInt value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization
    protected static String doString(String value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @Specialization(guards = {"!isJSFunction(value)"}, replaces = "doObject")
    protected static TruffleObject doTruffleObject(TruffleObject value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        return value;
    }

    @TruffleBoundary
    @Specialization(guards = {"!isTruffleObject(thiz)", "!isString(thiz)", "!isBoolean(thiz)", "!isNumberDouble(thiz)", "!isNumberLong(thiz)", "!isNumberInteger(thiz)"})
    protected static Object doOther(Object value, @SuppressWarnings("unused") Object thiz, @SuppressWarnings("unused") boolean bindFunctions) {
        throw Errors.createTypeErrorFormat("Cannot convert to TruffleObject: %s", value == null ? null : value.getClass().getSimpleName());
    }

    public static ExportValueNode create() {
        return ExportValueNodeGen.create();
    }

    public static ExportValueNode getUncached() {
        return ExportValueNodeGen.getUncached();
    }
}

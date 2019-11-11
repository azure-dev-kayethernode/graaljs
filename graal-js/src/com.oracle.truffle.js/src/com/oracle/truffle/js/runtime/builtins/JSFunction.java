/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.runtime.builtins;

import java.util.EnumSet;
import java.util.Iterator;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.LocationModifier;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.builtins.AsyncFromSyncIteratorPrototypeBuiltins;
import com.oracle.truffle.js.builtins.AsyncGeneratorPrototypeBuiltins;
import com.oracle.truffle.js.builtins.ConstructorBuiltins;
import com.oracle.truffle.js.builtins.EnumerateIteratorPrototypeBuiltins;
import com.oracle.truffle.js.builtins.ForInIteratorPrototypeBuiltins;
import com.oracle.truffle.js.builtins.FunctionPrototypeBuiltins;
import com.oracle.truffle.js.builtins.GeneratorPrototypeBuiltins;
import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSContext.BuiltinFunctionKey;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.objects.Accessor;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.objects.PropertyProxy;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.ForInIterator;

public final class JSFunction extends JSBuiltinObject {

    public static final String TYPE_NAME = "function";
    public static final String CLASS_NAME = "Function";
    public static final String CLASS_NAME_NASHORN_COMPAT = "FunctionNashornCompat";
    public static final String PROTOTYPE_NAME = "Function.prototype";
    public static final String GENERATOR_FUNCTION_NAME = "GeneratorFunction";
    public static final String GENERATOR_NAME = "Generator";
    public static final String GENERATOR_PROTOTYPE_NAME = "Generator.prototype";
    public static final String ASYNC_FUNCTION_NAME = "AsyncFunction";
    public static final String ASYNC_GENERATOR_FUNCTION_NAME = "AsyncGeneratorFunction";
    public static final String ASYNC_GENERATOR_NAME = "AsyncGenerator";
    public static final String ASYNC_GENERATOR_PROTOTYPE_NAME = "AsyncGenerator.prototype";
    public static final String ENUMERATE_ITERATOR_PROTOTYPE_NAME = "[[Enumerate]].prototype";
    public static final String FOR_IN_ITERATOR_PROTOYPE_NAME = "%ForInIteratorPrototype%";
    public static final String CALLER = "caller";
    public static final String ARGUMENTS = "arguments";
    public static final String LENGTH = "length";
    public static final String NAME = "name";
    public static final String PROGRAM_FUNCTION_NAME = ":program";

    public static final String BUILTIN_SOURCE_NAME = "<builtin>";
    public static final SourceSection BUILTIN_SOURCE_SECTION = createBuiltinSourceSection(BUILTIN_SOURCE_NAME);

    public static final HiddenKey ASYNC_FROM_SYNC_ITERATOR_KEY = new HiddenKey("SyncIterator");
    public static final String ASYNC_FROM_SYNC_ITERATOR_PROTOTYPE_NAME = "%AsyncFromSyncIteratorPrototype%";

    private static final Property PROTOTYPE_PROPERTY_WRITABLE;
    private static final Property PROTOTYPE_PROPERTY_NOT_WRITABLE;
    private static final Property LENGTH_PROPERTY;
    private static final Property LENGTH_PROPERTY_NOT_CONFIGURABLE;
    private static final Property NAME_PROPERTY;

    private static final PropertyProxy PROTOTYPE_PROXY = new ClassPrototypeProxyProperty();

    public static class FunctionLengthPropertyProxy implements PropertyProxy {
        @Override
        public Object get(DynamicObject store) {
            assert JSFunction.isJSFunction(store);
            if (JSFunction.isBoundFunction(store)) {
                return getBoundFunctionLength(store);
            }
            return JSFunction.getLength(store);
        }

        public int getProfiled(DynamicObject store, BranchProfile isBoundBranch) {
            assert JSFunction.isJSFunction(store);
            if (JSFunction.isBoundFunction(store)) {
                isBoundBranch.enter();
                return getBoundFunctionLength(store);
            }
            return JSFunction.getLength(store);
        }

        @TruffleBoundary
        private int getBoundFunctionLength(DynamicObject store) {
            if (JSFunction.isBoundFunction(store)) {
                return Math.max(0, getBoundFunctionLength(JSFunction.getBoundTargetFunction(store)) - JSFunction.getBoundArguments(store).length);
            } else {
                return JSFunction.getLength(store);
            }
        }
    }

    private static final PropertyProxy LENGTH_PROXY = new FunctionLengthPropertyProxy();

    public static class FunctionNamePropertyProxy implements PropertyProxy {
        @Override
        public Object get(DynamicObject store) {
            assert JSFunction.isJSFunction(store);
            if (JSFunction.isBoundFunction(store)) {
                return getBoundFunctionName(store);
            }
            return JSFunction.getName(store);
        }

        public String getProfiled(DynamicObject store, BranchProfile isBoundBranch) {
            assert JSFunction.isJSFunction(store);
            if (JSFunction.isBoundFunction(store)) {
                isBoundBranch.enter();
                return getBoundFunctionName(store);
            }
            return JSFunction.getName(store);
        }

        @TruffleBoundary
        private String getBoundFunctionName(DynamicObject store) {
            if (JSFunction.isBoundFunction(store)) {
                return "bound " + getBoundFunctionName(JSFunction.getBoundTargetFunction(store));
            } else {
                return JSFunction.getName(store);
            }
        }
    }

    private static final PropertyProxy NAME_PROXY = new FunctionNamePropertyProxy();

    /** Placeholder for lazy initialization of the prototype property. */
    private static final Object CLASS_PROTOTYPE_PLACEHOLDER = new Object();

    public static final JSFunction INSTANCE = new JSFunction();

    /** Materialized frame of the enclosing function. */
    private static final Property ENCLOSING_FRAME_PROPERTY;
    private static final HiddenKey ENCLOSING_FRAME = new HiddenKey("enclosingFrame");
    /** Shared function data. */
    private static final Property FUNCTION_DATA_PROPERTY;
    private static final HiddenKey FUNCTION_DATA = new HiddenKey("functionData");
    /** The {@code prototype} property of the function object. Lazily initialized. */
    private static final Property CLASS_PROTOTYPE_PROPERTY;
    private static final HiddenKey CLASS_PROTOTYPE = new HiddenKey("classPrototype");

    public static final HiddenKey REALM_ID = new HiddenKey("Realm");
    private static final Property REALM_PROPERTY;

    public static final HiddenKey HOME_OBJECT_ID = new HiddenKey("HomeObject");

    public static final HiddenKey GENERATOR_STATE_ID = new HiddenKey("GeneratorState");
    public static final HiddenKey GENERATOR_CONTEXT_ID = new HiddenKey("GeneratorContext");
    public static final HiddenKey GENERATOR_TARGET_ID = new HiddenKey("GeneratorTarget");

    public static final HiddenKey ASYNC_GENERATOR_STATE_ID = new HiddenKey("AsyncGeneratorState");
    public static final HiddenKey ASYNC_GENERATOR_CONTEXT_ID = new HiddenKey("AsyncGeneratorContext");
    public static final HiddenKey ASYNC_GENERATOR_QUEUE_ID = new HiddenKey("AsyncGeneratorQueue");
    public static final HiddenKey ASYNC_GENERATOR_TARGET_ID = new HiddenKey("AsyncGeneratorTarget");

    /** Marker property to ensure generator function shapes are distinct from normal functions. */
    private static final Property GENERATOR_FUNCTION_MARKER_PROPERTY;
    private static final HiddenKey GENERATOR_FUNCTION_MARKER_ID = new HiddenKey("generator function");
    private static final Property ASYNC_GENERATOR_FUNCTION_MARKER_PROPERTY;
    private static final HiddenKey ASYNC_GENERATOR_FUNCTION_MARKER_ID = new HiddenKey("async generator function");

    /** Internal Slots of Exotic Bound Function Objects. **/
    private static final HiddenKey BOUND_ARGUMENTS = new HiddenKey("BoundArguments");
    private static final HiddenKey BOUND_THIS = new HiddenKey("BoundThis");
    private static final HiddenKey BOUND_TARGET_FUNCTION = new HiddenKey("BoundTargetFunction");
    private static final Property BOUND_ARGUMENTS_PROPERTY;
    private static final Property BOUND_THIS_PROPERTY;
    private static final Property BOUND_TARGET_FUNCTION_PROPERTY;

    public enum GeneratorState {
        SuspendedStart,
        SuspendedYield,
        Executing,
        Completed,
    }

    public enum AsyncGeneratorState {
        SuspendedStart,
        SuspendedYield,
        Executing,
        AwaitingReturn,
        Completed,
    }

    static {
        Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
        FUNCTION_DATA_PROPERTY = JSObjectUtil.makeHiddenProperty(FUNCTION_DATA, allocator.locationForType(JSFunctionData.class, EnumSet.of(LocationModifier.Final, LocationModifier.NonNull)));
        ENCLOSING_FRAME_PROPERTY = JSObjectUtil.makeHiddenProperty(ENCLOSING_FRAME, allocator.locationForType(MaterializedFrame.class, EnumSet.of(LocationModifier.Final, LocationModifier.NonNull)));
        CLASS_PROTOTYPE_PROPERTY = JSObjectUtil.makeHiddenProperty(CLASS_PROTOTYPE, allocator.locationForType(Object.class, EnumSet.of(LocationModifier.NonNull)));
        REALM_PROPERTY = JSObjectUtil.makeHiddenProperty(REALM_ID, allocator.locationForType(JSRealm.class, EnumSet.of(LocationModifier.NonNull)));

        BOUND_TARGET_FUNCTION_PROPERTY = JSObjectUtil.makeHiddenProperty(BOUND_TARGET_FUNCTION, allocator.locationForType(DynamicObject.class, EnumSet.of(LocationModifier.NonNull)));
        BOUND_THIS_PROPERTY = JSObjectUtil.makeHiddenProperty(BOUND_THIS, allocator.locationForType(Object.class, EnumSet.of(LocationModifier.NonNull)));
        BOUND_ARGUMENTS_PROPERTY = JSObjectUtil.makeHiddenProperty(BOUND_ARGUMENTS, allocator.locationForType(Object[].class, EnumSet.of(LocationModifier.NonNull)));

        PROTOTYPE_PROPERTY_WRITABLE = JSObjectUtil.makeProxyProperty(JSObject.PROTOTYPE, PROTOTYPE_PROXY, JSAttributes.notConfigurableNotEnumerableWritable());
        PROTOTYPE_PROPERTY_NOT_WRITABLE = JSObjectUtil.makeProxyProperty(JSObject.PROTOTYPE, PROTOTYPE_PROXY, JSAttributes.notConfigurableNotEnumerableNotWritable());
        LENGTH_PROPERTY = JSObjectUtil.makeProxyProperty(LENGTH, LENGTH_PROXY, JSAttributes.configurableNotEnumerableNotWritable());
        LENGTH_PROPERTY_NOT_CONFIGURABLE = JSObjectUtil.makeProxyProperty(LENGTH, LENGTH_PROXY, JSAttributes.notConfigurableNotEnumerableNotWritable());
        NAME_PROPERTY = JSObjectUtil.makeProxyProperty(NAME, NAME_PROXY, JSAttributes.configurableNotEnumerableNotWritable());

        GENERATOR_FUNCTION_MARKER_PROPERTY = JSObjectUtil.makeHiddenProperty(GENERATOR_FUNCTION_MARKER_ID, allocator.constantLocation(null));
        ASYNC_GENERATOR_FUNCTION_MARKER_PROPERTY = JSObjectUtil.makeHiddenProperty(ASYNC_GENERATOR_FUNCTION_MARKER_ID, allocator.constantLocation(null));
    }

    private JSFunction() {
    }

    public static CallTarget getCallTarget(DynamicObject obj) {
        return getFunctionData(obj).getCallTarget();
    }

    public static MaterializedFrame getEnclosingFrame(DynamicObject obj) {
        return getEnclosingFrame(obj, isJSFunction(obj));
    }

    public static MaterializedFrame getEnclosingFrame(DynamicObject obj, boolean floatingCondition) {
        assert isJSFunction(obj);
        return (MaterializedFrame) ENCLOSING_FRAME_PROPERTY.get(obj, floatingCondition);
    }

    public static JSFunctionData getFunctionData(DynamicObject obj) {
        return getFunctionData(obj, isJSFunction(obj));
    }

    public static JSFunctionData getFunctionData(DynamicObject obj, boolean floatingCondition) {
        assert isJSFunction(obj);
        return (JSFunctionData) FUNCTION_DATA_PROPERTY.get(obj, floatingCondition);
    }

    private static Object getClassPrototypeField(DynamicObject obj) {
        assert isJSFunction(obj);
        return CLASS_PROTOTYPE_PROPERTY.get(obj, isJSFunction(obj));
    }

    private static void setClassPrototypeField(DynamicObject obj, Object classPrototype) {
        assert isJSFunction(obj);
        CLASS_PROTOTYPE_PROPERTY.setSafe(obj, classPrototype, null);
    }

    public static JSRealm getRealm(DynamicObject obj) {
        return getRealm(obj, isJSFunction(obj));
    }

    public static JSRealm getRealm(DynamicObject obj, boolean floatingCondition) {
        assert isJSFunction(obj);
        return (JSRealm) REALM_PROPERTY.get(obj, floatingCondition);
    }

    /**
     * Version optimized for a single Realm.
     */
    public static JSRealm getRealm(DynamicObject functionObj, JSContext context) {
        assert isJSFunction(functionObj);
        JSRealm realm;
        if (context.isSingleRealm()) {
            realm = context.getRealm();
            assert realm == getRealm(functionObj);
        } else {
            realm = getRealm(functionObj);
        }
        return realm;
    }

    public static DynamicObject create(JSRealm realm, JSFunctionData functionData) {
        return create(realm, functionData, JSFrameUtil.NULL_MATERIALIZED_FRAME);
    }

    public static DynamicObject create(JSRealm realm, JSFunctionData functionData, MaterializedFrame enclosingFrame) {
        return createDefault(functionData, enclosingFrame, CLASS_PROTOTYPE_PLACEHOLDER, realm);
    }

    public static DynamicObject createWithPrototype(JSFunctionFactory factory, JSRealm realm, JSFunctionData functionData, MaterializedFrame enclosingFrame, DynamicObject prototype) {
        return createWithPrototype(factory, functionData, enclosingFrame, CLASS_PROTOTYPE_PLACEHOLDER, realm, prototype);
    }

    public static DynamicObject createLexicalThis(JSRealm realm, JSFunctionData functionData, MaterializedFrame enclosingFrame, Object lexicalThis) {
        return createDefault(functionData, enclosingFrame, lexicalThis, realm);
    }

    private static DynamicObject createDefault(JSFunctionData functionData, MaterializedFrame enclosingFrame, Object classPrototype, JSRealm realm) {
        JSFunctionFactory factory = initialFactory(functionData);
        return factory.create(functionData, enclosingFrame, classPrototype, realm);
    }

    private static DynamicObject createWithPrototype(JSFunctionFactory factory, JSFunctionData functionData, MaterializedFrame enclosingFrame, Object classPrototype, JSRealm realm,
                    DynamicObject prototype) {
        return factory.createWithPrototype(functionData, enclosingFrame, classPrototype, realm, prototype);
    }

    public static DynamicObject createBound(JSContext context, JSRealm realm, JSFunctionData functionData, DynamicObject boundTargetFunction, Object boundThis, Object[] boundArguments) {
        assert functionData != null;
        JSFunctionFactory factory = context.getBoundFunctionFactory(functionData);
        return factory.createBound(functionData, CLASS_PROTOTYPE_PLACEHOLDER, realm, boundTargetFunction, boundThis, boundArguments);
    }

    private static JSFunctionFactory initialFactory(JSFunctionData functionData) {
        return functionData.getContext().getFunctionFactory(functionData);
    }

    public static String getName(DynamicObject obj) {
        return getFunctionData(obj).getName();
    }

    public static Object call(DynamicObject functionObject, Object thisObject, Object[] argumentValues) {
        assert JSFunction.isJSFunction(functionObject);
        assert thisObject != null;
        Object[] arguments = JSArguments.create(thisObject, functionObject, argumentValues);
        return getCallTarget(functionObject).call(arguments);
    }

    public static Object call(Object[] jsArguments) {
        assert JSFunction.isJSFunction(JSArguments.getFunctionObject(jsArguments));
        assert JSArguments.getThisObject(jsArguments) != null;
        return getCallTarget((DynamicObject) JSArguments.getFunctionObject(jsArguments)).call(jsArguments);
    }

    public static Object construct(DynamicObject functionObject, Object[] argumentValues) {
        assert isJSFunction(functionObject) && isConstructor(functionObject);
        Object[] arguments = JSArguments.create(CONSTRUCT, functionObject, argumentValues);
        return getConstructTarget(functionObject).call(arguments);
    }

    @TruffleBoundary
    public static DynamicObject bind(JSRealm realm, DynamicObject thisFnObj, Object thisArg, Object[] boundArguments) {
        assert JSFunction.isJSFunction(thisFnObj);
        JSContext context = realm.getContext();
        DynamicObject proto = JSObject.getPrototype(thisFnObj);
        DynamicObject boundFunction = boundFunctionCreate(context, thisFnObj, thisArg, boundArguments, proto, null, null);

        long length = 0;
        boolean targetHasLength = JSObject.hasOwnProperty(thisFnObj, JSFunction.LENGTH);
        boolean mustSetLength = true;
        if (targetHasLength) {
            Object targetLen = JSObject.get(thisFnObj, JSFunction.LENGTH);
            if (JSRuntime.isNumber(targetLen)) {
                long targetLenInt = JSRuntime.toInteger(targetLen);
                length = Math.max(0, targetLenInt - boundArguments.length);
                if (targetLenInt == getLength(thisFnObj)) {
                    mustSetLength = false;
                }
            }
        }
        if (mustSetLength) {
            setFunctionLength(boundFunction, JSRuntime.longToIntOrDouble(length));
        }

        String targetName = getFunctionName(thisFnObj);
        if (!targetName.equals(getName(thisFnObj))) {
            setBoundFunctionName(boundFunction, targetName);
        }
        return boundFunction;
    }

    public static DynamicObject boundFunctionCreate(JSContext context, DynamicObject boundTargetFunction, Object boundThis, Object[] boundArguments, DynamicObject proto,
                    ConditionProfile isAsyncProfile, ConditionProfile setProtoProfile) {
        assert JSFunction.isJSFunction(boundTargetFunction);
        CompilerAsserts.partialEvaluationConstant(context);

        boolean constructor = JSFunction.isConstructor(boundTargetFunction);
        JSFunctionData functionData = context.getBoundFunctionData(constructor);
        boolean isAsync = JSFunction.getFunctionData(boundTargetFunction).isAsync();
        if ((isAsyncProfile == null ? isAsync : isAsyncProfile.profile(isAsync))) {
            int length = Math.max(0, JSFunction.getLength(boundTargetFunction) - boundArguments.length);
            functionData = makeBoundFunctionData(context, length, constructor, isAsync);
        }
        JSRealm realm = getRealm(boundTargetFunction, context);
        DynamicObject boundFunction = JSFunction.createBound(context, realm, functionData, boundTargetFunction, boundThis, boundArguments);
        boolean needSetProto = proto != realm.getFunctionPrototype();
        if ((setProtoProfile == null ? needSetProto : setProtoProfile.profile(needSetProto))) {
            JSObject.setPrototype(boundFunction, proto);
        }
        assert JSObject.getPrototype(boundFunction) == proto;
        return boundFunction;
    }

    @TruffleBoundary
    private static JSFunctionData makeBoundFunctionData(JSContext context, int length, boolean constructor, boolean isAsync) {
        return JSFunctionData.create(context,
                        context.getBoundFunctionCallTarget(), context.getBoundFunctionConstructTarget(), context.getBoundFunctionConstructNewTarget(),
                        length, "bound", constructor, false, true, false, false, false, isAsync, false, true, false, true);
    }

    @TruffleBoundary
    private static String getFunctionName(DynamicObject thisFnObj) {
        Object name = JSObject.get(thisFnObj, NAME);
        if (!JSRuntime.isString(name)) {
            name = "";
        }
        return name.toString();
    }

    @TruffleBoundary
    public static void setFunctionLength(DynamicObject functionObj, Number length) {
        JSObject.defineOwnProperty(functionObj, JSFunction.LENGTH, PropertyDescriptor.createData(length, false, false, true));
    }

    @TruffleBoundary
    public static void setBoundFunctionName(DynamicObject boundFunction, String targetName) {
        JSObject.defineOwnProperty(boundFunction, JSFunction.NAME, PropertyDescriptor.createData("bound " + targetName, false, false, true));
    }

    public static boolean isStrict(DynamicObject obj) {
        return getFunctionData(obj).isStrict();
    }

    public static boolean isBuiltin(DynamicObject obj) {
        return getFunctionData(obj).isBuiltin();
    }

    public static boolean isConstructor(DynamicObject obj) {
        assert JSFunction.isJSFunction(obj);
        return getFunctionData(obj).isConstructor();
    }

    public static boolean isConstructor(Object obj) {
        return JSFunction.isJSFunction(obj) && getFunctionData((DynamicObject) obj).isConstructor();
    }

    public static boolean isGenerator(DynamicObject obj) {
        return getFunctionData(obj).isGenerator();
    }

    public static boolean needsParentFrame(DynamicObject obj) {
        return getFunctionData(obj).needsParentFrame();
    }

    public static int getLength(DynamicObject obj) {
        return getFunctionData(obj).getLength();
    }

    public static boolean isClassPrototypeInitialized(DynamicObject thisObj) {
        return getClassPrototypeField(thisObj) != CLASS_PROTOTYPE_PLACEHOLDER;
    }

    public static boolean isBoundFunction(DynamicObject function) {
        return isJSFunction(function) && getFunctionData(function).isBound();
    }

    public static boolean isAsyncFunction(DynamicObject function) {
        return isJSFunction(function) && getFunctionData(function).isAsync();
    }

    public static Object getBoundThis(DynamicObject function) {
        assert isBoundFunction(function);
        return BOUND_THIS_PROPERTY.get(function, isBoundFunction(function));
    }

    public static DynamicObject getBoundTargetFunction(DynamicObject function) {
        assert isBoundFunction(function);
        return (DynamicObject) BOUND_TARGET_FUNCTION_PROPERTY.get(function, isBoundFunction(function));
    }

    public static Object[] getBoundArguments(DynamicObject function) {
        assert isBoundFunction(function);
        return (Object[]) BOUND_ARGUMENTS_PROPERTY.get(function, isBoundFunction(function));
    }

    public static Object getLexicalThis(DynamicObject thisObj) {
        return getClassPrototypeInitialized(thisObj);
    }

    public static Object getClassPrototypeInitialized(DynamicObject thisObj) {
        Object classPrototype = getClassPrototypeField(thisObj);
        assert classPrototype != CLASS_PROTOTYPE_PLACEHOLDER;
        return classPrototype;
    }

    public static Object getClassPrototype(DynamicObject thisObj) {
        Object classPrototype = getClassPrototypeField(thisObj);
        if (classPrototype == CLASS_PROTOTYPE_PLACEHOLDER) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initializeClassPrototype(thisObj);
        }
        return getClassPrototypeField(thisObj);
    }

    private static void initializeClassPrototype(DynamicObject thisObj) {
        setClassPrototypeField(thisObj, createPrototype(thisObj));
    }

    private static DynamicObject createPrototype(DynamicObject constructor) {
        JSFunctionData functionData = getFunctionData(constructor);
        JSRealm realm = getRealm(constructor);
        JSContext context = functionData.getContext();
        if (!functionData.isGenerator()) {
            DynamicObject prototype = JSUserObject.create(context, realm);
            JSObjectUtil.putConstructorProperty(context, prototype, constructor);
            return prototype;
        } else {
            return JSObject.createWithRealm(context, functionData.isAsync() ? context.getAsyncGeneratorObjectFactory() : context.getGeneratorObjectFactory(), realm);
        }
    }

    public static void setClassPrototype(DynamicObject thisObj, Object value) {
        assert value != null;
        setClassPrototypeField(thisObj, value);
    }

    public static final class ClassPrototypeProxyProperty implements PropertyProxy {
        private ClassPrototypeProxyProperty() {
        }

        @Override
        public boolean set(DynamicObject store, Object value) {
            assert JSFunction.isJSFunction(store);
            JSFunction.setClassPrototype(store, value);
            return true;
        }

        @Override
        public Object get(DynamicObject store) {
            assert JSFunction.isJSFunction(store);
            return JSFunction.getClassPrototype(store);
        }
    }

    static class BoundRootNode extends JavaScriptRootNode {
        private static final SourceSection SOURCE_SECTION = createBuiltinSourceSection("bound function");

        @Child protected IndirectCallNode callNode;
        protected final BranchProfile initProfile = BranchProfile.create();

        BoundRootNode(JSContext context) {
            super(context.getLanguage(), SOURCE_SECTION, null);
            this.callNode = Truffle.getRuntime().createIndirectCallNode();
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] originalArguments = frame.getArguments();
            DynamicObject boundFunction = castBoundFunction(JSArguments.getFunctionObject(originalArguments));
            DynamicObject boundTargetFunction = getBoundTargetFunction(boundFunction);
            Object[] boundArguments = getBoundArguments(boundFunction);
            Object boundThis = getBoundThis(boundFunction);
            Object[] argumentValues = JSArguments.extractUserArguments(originalArguments);
            Object[] arguments = prependBoundArguments(boundArguments, argumentValues);
            Object[] newArguments = JSArguments.create(boundThis, boundTargetFunction, arguments);
            return callNode.call(JSFunction.getFunctionData(boundTargetFunction).getCallTarget(initProfile), newArguments);
        }

        protected static Object[] prependBoundArguments(Object[] boundArguments, Object[] argumentValues) {
            Object[] arguments = new Object[boundArguments.length + argumentValues.length];
            System.arraycopy(boundArguments, 0, arguments, 0, boundArguments.length);
            System.arraycopy(argumentValues, 0, arguments, boundArguments.length, argumentValues.length);
            return arguments;
        }

        protected static DynamicObject castBoundFunction(Object functionObj) {
            DynamicObject boundFunction = (DynamicObject) functionObj;
            if (!isBoundFunction(boundFunction)) {
                throw Errors.shouldNotReachHere();
            }
            return boundFunction;
        }
    }

    static final class BoundConstructRootNode extends BoundRootNode {
        BoundConstructRootNode(JSContext context) {
            super(context);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] originalArguments = frame.getArguments();
            DynamicObject boundFunction = castBoundFunction(JSArguments.getFunctionObject(originalArguments));
            DynamicObject boundTargetFunction = getBoundTargetFunction(boundFunction);
            Object[] boundArguments = getBoundArguments(boundFunction);
            Object[] argumentValues = JSArguments.extractUserArguments(originalArguments);
            Object[] arguments = prependBoundArguments(boundArguments, argumentValues);
            Object originalThis = JSArguments.getThisObject(originalArguments);
            Object[] newArguments = JSArguments.create(originalThis, boundTargetFunction, arguments);
            return callNode.call(JSFunction.getFunctionData(boundTargetFunction).getConstructTarget(initProfile), newArguments);
        }
    }

    static final class BoundConstructNewTargetRootNode extends BoundRootNode {
        BoundConstructNewTargetRootNode(JSContext context) {
            super(context);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] originalArguments = frame.getArguments();
            DynamicObject boundFunction = castBoundFunction(JSArguments.getFunctionObject(originalArguments));
            DynamicObject boundTargetFunction = getBoundTargetFunction(boundFunction);
            Object[] boundArguments = getBoundArguments(boundFunction);
            Object[] argumentValues = JSArguments.extractUserArguments(originalArguments, 1);
            Object[] arguments = prependBoundArguments(boundArguments, argumentValues);
            Object originalThis = JSArguments.getThisObject(originalArguments);
            Object newTarget = JSArguments.getNewTarget(originalArguments);
            if (newTarget == boundFunction) {
                newTarget = boundTargetFunction;
            }
            Object[] newArguments = JSArguments.createWithNewTarget(originalThis, boundTargetFunction, newTarget, arguments);
            return callNode.call(JSFunction.getFunctionData(boundTargetFunction).getConstructNewTarget(initProfile), newArguments);
        }
    }

    public static RootNode createBoundRootNode(JSContext context, boolean construct, boolean newTarget) {
        if (newTarget) {
            return new BoundConstructNewTargetRootNode(context);
        } else if (construct) {
            return new BoundConstructRootNode(context);
        } else {
            return new BoundRootNode(context);
        }
    }

    public static DynamicObject createFunctionPrototype(JSRealm realm, DynamicObject objectPrototype) {
        JSContext context = realm.getContext();
        DynamicObject obj = JSObject.createInit(realm, objectPrototype, INSTANCE);
        JSObjectUtil.putHiddenProperty(obj, FUNCTION_DATA_PROPERTY, createEmptyFunctionData(context));
        JSObjectUtil.putHiddenProperty(obj, ENCLOSING_FRAME_PROPERTY, JSFrameUtil.NULL_MATERIALIZED_FRAME);
        JSObjectUtil.putHiddenProperty(obj, CLASS_PROTOTYPE_PROPERTY, CLASS_PROTOTYPE_PLACEHOLDER);
        JSObjectUtil.putHiddenProperty(obj, REALM_PROPERTY, realm);
        JSObjectUtil.putProxyProperty(obj, LENGTH_PROPERTY);
        JSObjectUtil.putProxyProperty(obj, NAME_PROPERTY);
        return obj;
    }

    public static void addRestrictedFunctionProperties(JSRealm realm, DynamicObject obj) {
        if (!realm.getEnv().isPreInitialization()) {
            // Do not request the thrower function during context pre-initialization.
            // Constructing the thrower functions triggers the setup of the shape of anonymous
            // functions. However, the shape of anonymous functions depends on the v8-compat option.
            // Therefore, when using pre-initialized contexts, we defer this part of the setup
            // to context-patching time.
            JSObjectUtil.putConstantAccessorProperty(realm.getContext(), obj, CALLER, realm.getThrowerFunction(), realm.getThrowerFunction());
            JSObjectUtil.putConstantAccessorProperty(realm.getContext(), obj, ARGUMENTS, realm.getThrowerFunction(), realm.getThrowerFunction());
        }
    }

    public static JSFunctionData createNamedEmptyFunctionData(JSContext context, String name) {
        return JSFunctionData.createCallOnly(context, context.getEmptyFunctionCallTarget(), 0, name);
    }

    public static JSFunctionData createEmptyFunctionData(JSContext context) {
        return createNamedEmptyFunctionData(context, "");
    }

    public static DynamicObject createNamedEmptyFunction(JSRealm realm, String name) {
        return JSFunction.create(realm, createNamedEmptyFunctionData(realm.getContext(), name));
    }

    public static DynamicObject createEmptyFunction(JSRealm realm) {
        return JSFunction.create(realm, createEmptyFunctionData(realm.getContext()));
    }

    public static void fillFunctionPrototype(JSRealm realm) {
        JSContext ctx = realm.getContext();
        JSObjectUtil.putConstructorProperty(ctx, realm.getFunctionPrototype(), realm.getFunctionConstructor());
        JSObjectUtil.putFunctionsFromContainer(realm, realm.getFunctionPrototype(), FunctionPrototypeBuiltins.BUILTINS);
        if (ctx.getEcmaScriptVersion() >= 6) {
            addRestrictedFunctionProperties(realm, realm.getFunctionPrototype());
        }
        if (ctx.isOptionNashornCompatibilityMode()) {
            JSObjectUtil.putFunctionsFromContainer(realm, realm.getFunctionPrototype(), FunctionPrototypeBuiltins.BUILTINS_NASHORN_COMPAT);
        }
    }

    private static Shape makeBaseFunctionShape(JSContext context, DynamicObject prototype) {
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, INSTANCE, context);
        initialShape = initialShape.reservePrimitiveExtensionArray();
        initialShape = initialShape.addProperty(FUNCTION_DATA_PROPERTY);
        initialShape = initialShape.addProperty(ENCLOSING_FRAME_PROPERTY);
        initialShape = initialShape.addProperty(CLASS_PROTOTYPE_PROPERTY);
        initialShape = initialShape.addProperty(REALM_PROPERTY);
        return initialShape;
    }

    private static Shape addCallerAndArgumentsProperties(Shape initialShape, JSContext context, boolean isStrict) {
        if (context.getEcmaScriptVersion() >= 6) {
            if (!isStrict) {
                return makeNonStrictFunctionShape(initialShape, context);
            }
        } else {
            if (isStrict) {
                return makeES5StrictFunctionShape(initialShape, context);
            }
        }
        return initialShape;
    }

    private static Shape addLengthProxyProperty(Shape initialShape, JSContext context) {
        return initialShape.addProperty(context.getEcmaScriptVersion() < 6 ? LENGTH_PROPERTY_NOT_CONFIGURABLE : LENGTH_PROPERTY);
    }

    private static Shape addNameProxyProperty(Shape initialShape) {
        return initialShape.addProperty(NAME_PROPERTY);
    }

    public static Shape makeInitialFunctionShape(JSContext context, DynamicObject prototype, boolean isStrict) {
        return makeInitialFunctionShape(context, prototype, isStrict, false, false);
    }

    public static Shape makeInitialFunctionShape(JSContext context, DynamicObject prototype, boolean isStrict, boolean hasPrototype, boolean prototypeNotWritable) {
        Shape initialShape = makeBaseFunctionShape(context, prototype);
        initialShape = addLengthProxyProperty(initialShape, context);
        if (hasPrototype && prototypeNotWritable) {
            initialShape = addPrototypeProxyProperty(initialShape, prototypeNotWritable);
        }
        initialShape = addNameProxyProperty(initialShape);
        if (hasPrototype && !prototypeNotWritable) {
            // off-spec: add prototype property after name for compatibility with V8
            initialShape = addPrototypeProxyProperty(initialShape, prototypeNotWritable);
        }
        initialShape = addCallerAndArgumentsProperties(initialShape, context, isStrict);
        return initialShape;
    }

    /**
     * Add prototype property to an initial function shape.
     *
     * @param functionShape an initial function shape without a prototype property.
     * @param notWritable prototype property is non-writable; {@code true} for class constructors.
     */
    private static Shape addPrototypeProxyProperty(Shape functionShape, boolean notWritable) {
        assert JSShape.getJSClassNoCast(functionShape) == INSTANCE;
        if (notWritable) {
            return functionShape.addProperty(PROTOTYPE_PROPERTY_NOT_WRITABLE);
        } else {
            return functionShape.addProperty(PROTOTYPE_PROPERTY_WRITABLE);
        }
    }

    /**
     * Set arguments and caller properties of non-strict function objects.
     *
     * Note: Nowhere in ES6 is specified that we have to set these properties, we do it for
     * compatibility reasons.
     */
    private static Shape makeNonStrictFunctionShape(Shape shape, JSContext context) {
        Shape nonStrictShape = shape;
        nonStrictShape = nonStrictShape.addProperty(JSObjectUtil.makeProxyProperty(ARGUMENTS, new ArgumentsProxyProperty(context), JSAttributes.notConfigurableNotEnumerableNotWritable()));
        nonStrictShape = nonStrictShape.addProperty(JSObjectUtil.makeProxyProperty(CALLER, new CallerProxyProperty(context), JSAttributes.notConfigurableNotEnumerableNotWritable()));
        return nonStrictShape;
    }

    /**
     * Set arguments and caller properties of strict function objects. ES5 Legacy.
     */
    private static Shape makeES5StrictFunctionShape(Shape nonStrictShape, JSContext context) {
        assert context.getEcmaScriptVersion() < 6;
        Shape strictShape = nonStrictShape;
        strictShape = strictShape.addProperty(JSObjectUtil.makeAccessorProperty(ARGUMENTS,
                        strictShape.allocator().locationForType(Accessor.class, EnumSet.of(LocationModifier.Final, LocationModifier.NonNull)), JSAttributes.notConfigurableNotEnumerable()));
        strictShape = strictShape.addProperty(JSObjectUtil.makeAccessorProperty(CALLER,
                        strictShape.allocator().locationForType(Accessor.class, EnumSet.of(LocationModifier.Final, LocationModifier.NonNull)), JSAttributes.notConfigurableNotEnumerable()));
        return strictShape;
    }

    public static DynamicObject createFunctionConstructor(JSRealm realm) {
        JSContext ctx = realm.getContext();
        DynamicObject functionConstructor = realm.lookupFunction(ConstructorBuiltins.BUILTINS, CLASS_NAME);
        JSObjectUtil.putDataProperty(ctx, functionConstructor, JSObject.PROTOTYPE, realm.getFunctionPrototype(), JSAttributes.notConfigurableNotEnumerableNotWritable());
        return functionConstructor;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return CLASS_NAME;
    }

    @Override
    public String getBuiltinToStringTag(DynamicObject object) {
        return getClassName(object);
    }

    @Override
    @TruffleBoundary
    public String safeToString(DynamicObject obj, int depth) {
        RootNode rn = ((RootCallTarget) JSFunction.getCallTarget(obj)).getRootNode();
        SourceSection ssect = rn.getSourceSection();
        String source;
        if (ssect == null || !ssect.isAvailable() || ssect.getSource().isInternal()) {
            source = "function " + JSFunction.getName(obj) + "() { [native code] }";
        } else if (depth <= 0) {
            source = "function " + JSFunction.getName(obj) + "() {...}";
        } else {
            if (ssect.getCharacters().length() > 200) {
                source = ssect.getCharacters().subSequence(0, 195) + "...<omitted>...\n}";
            } else {
                source = ssect.getCharacters().toString();
            }
        }
        return source;
    }

    @Override
    public boolean hasOnlyShapeProperties(DynamicObject obj) {
        return true;
    }

    public static CallTarget getConstructTarget(DynamicObject obj) {
        return getFunctionData(obj).getConstructTarget();
    }

    public static CallTarget getConstructNewTarget(DynamicObject obj) {
        return getFunctionData(obj).getConstructNewTarget();
    }

    /**
     * Construct token. Passed from the {@code new} node as {@code this} argument to built-in
     * functions to differentiate between a constructor and a normal call (i.e., [[Construct]] and
     * [[Call]] internal methods, see ES5 13.2.1 and 13.2.2). Must not be passed anywhere else.
     */
    public static final DynamicObject CONSTRUCT = JSObject.createStatic(JSShape.makeStaticRoot(JSObject.LAYOUT, new JSBuiltinObject() {

        public static final String CLASS_NAME = "CONSTRUCT";

        @Override
        public String getClassName(DynamicObject object) {
            return CLASS_NAME;
        }

        @Override
        public String toString() {
            return CLASS_NAME;
        }
    }, 0));

    public static boolean isJSFunction(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSFunction((DynamicObject) obj);
    }

    public static boolean isJSFunction(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    // ##### Generator functions

    public static DynamicObject createGeneratorFunctionPrototype(JSRealm realm, DynamicObject constructor) {
        JSContext ctx = realm.getContext();
        // intrinsic object %Generator%
        DynamicObject prototype = JSObject.createInit(realm, realm.getFunctionPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putDataProperty(ctx, prototype, JSObject.CONSTRUCTOR, constructor, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, prototype, JSObject.PROTOTYPE, createGeneratorPrototype(realm, prototype), JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, prototype, Symbol.SYMBOL_TO_STRING_TAG, GENERATOR_FUNCTION_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        return prototype;
    }

    private static DynamicObject createGeneratorPrototype(JSRealm realm, DynamicObject constructor) {
        JSContext ctx = realm.getContext();
        // intrinsic object %GeneratorPrototype%
        DynamicObject generatorPrototype = JSObject.createInit(realm, realm.getIteratorPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(realm, generatorPrototype, GeneratorPrototypeBuiltins.BUILTINS);
        JSObjectUtil.putDataProperty(ctx, generatorPrototype, JSObject.CONSTRUCTOR, constructor, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, generatorPrototype, Symbol.SYMBOL_TO_STRING_TAG, GENERATOR_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        return generatorPrototype;
    }

    public static JSConstructor createGeneratorFunctionConstructor(JSRealm realm) {
        JSContext ctx = realm.getContext();
        // intrinsic object %GeneratorFunction%
        DynamicObject constructor = realm.lookupFunction(ConstructorBuiltins.BUILTINS, GENERATOR_FUNCTION_NAME);
        JSObject.setPrototype(constructor, realm.getFunctionConstructor());
        DynamicObject prototype = createGeneratorFunctionPrototype(realm, constructor);
        JSObjectUtil.putDataProperty(ctx, constructor, JSObject.PROTOTYPE, prototype, JSAttributes.notConfigurableNotEnumerableNotWritable());
        return new JSConstructor(constructor, prototype);
    }

    static Shape makeInitialGeneratorFunctionShape(JSContext context, DynamicObject prototype, boolean isAsync) {
        Shape initialShape = makeBaseFunctionShape(context, prototype);
        initialShape = initialShape.addProperty(isAsync ? ASYNC_GENERATOR_FUNCTION_MARKER_PROPERTY : GENERATOR_FUNCTION_MARKER_PROPERTY);
        initialShape = addLengthProxyProperty(initialShape, context);
        initialShape = addPrototypeProxyProperty(initialShape, false);
        initialShape = addNameProxyProperty(initialShape);
        return initialShape;
    }

    // ##### Async functions

    public static DynamicObject createAsyncFunctionPrototype(JSRealm realm, DynamicObject constructor) {
        JSContext ctx = realm.getContext();
        // intrinsic object %AsyncFunctionPrototype%
        DynamicObject prototype = JSObject.createInit(realm, realm.getFunctionPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putDataProperty(ctx, prototype, JSObject.CONSTRUCTOR, constructor, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, prototype, Symbol.SYMBOL_TO_STRING_TAG, ASYNC_FUNCTION_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        return prototype;
    }

    public static JSConstructor createAsyncFunctionConstructor(JSRealm realm) {
        JSContext ctx = realm.getContext();
        // intrinsic constructor %AsyncFunction%
        DynamicObject constructor = realm.lookupFunction(ConstructorBuiltins.BUILTINS, ASYNC_FUNCTION_NAME);
        JSObject.setPrototype(constructor, realm.getFunctionConstructor());
        DynamicObject prototype = createAsyncFunctionPrototype(realm, constructor);
        JSObjectUtil.putDataProperty(ctx, constructor, JSObject.PROTOTYPE, prototype, JSAttributes.notConfigurableNotEnumerableNotWritable());
        return new JSConstructor(constructor, prototype);
    }

    /**
     * Creates the %AsyncIteratorPrototype% object (ES2018 11.1.2).
     */
    public static DynamicObject createAsyncIteratorPrototype(JSRealm realm) {
        JSContext context = realm.getContext();
        DynamicObject prototype = JSObject.createInit(realm, realm.getObjectPrototype(), JSUserObject.INSTANCE);
        JSFunctionData functionData = realm.getContext().getOrCreateBuiltinFunctionData(BuiltinFunctionKey.FunctionAsyncIterator, (c) -> {
            return JSFunctionData.createCallOnly(context, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {
                @Override
                public Object execute(VirtualFrame frame) {
                    return JSFrameUtil.getThisObj(frame);
                }
            }), 0, Symbol.SYMBOL_ASYNC_ITERATOR.toFunctionNameString());
        });
        DynamicObject asyncIterator = JSFunction.create(realm, functionData);
        JSObjectUtil.putDataProperty(context, prototype, Symbol.SYMBOL_ASYNC_ITERATOR, asyncIterator, JSAttributes.getDefaultNotEnumerable());
        return prototype;
    }

    /**
     * Creates the %AsyncFromSyncIteratorPrototype% object (ES2018 11.1.3.2).
     */
    public static DynamicObject createAsyncFromSyncIteratorPrototype(JSRealm realm) {
        DynamicObject prototype = JSObject.createInit(realm, realm.getObjectPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(realm, prototype, AsyncFromSyncIteratorPrototypeBuiltins.BUILTINS);
        return prototype;
    }

    public static DynamicObject createAsyncGeneratorFunctionPrototype(JSRealm realm, DynamicObject constructor) {
        JSContext ctx = realm.getContext();
        // intrinsic object %AsyncGenerator%
        DynamicObject prototype = JSObject.createInit(realm, realm.getFunctionPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putDataProperty(ctx, prototype, JSObject.CONSTRUCTOR, constructor, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, prototype, JSObject.PROTOTYPE, createAsyncGeneratorPrototype(realm, prototype), JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, prototype, Symbol.SYMBOL_TO_STRING_TAG, ASYNC_GENERATOR_FUNCTION_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        return prototype;
    }

    private static DynamicObject createAsyncGeneratorPrototype(JSRealm realm, DynamicObject constructor) {
        JSContext ctx = realm.getContext();
        // intrinsic object %AsyncGeneratorPrototype%
        DynamicObject prototype = JSObject.createInit(realm, realm.getAsyncIteratorPrototype(), JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(realm, prototype, AsyncGeneratorPrototypeBuiltins.BUILTINS);
        JSObjectUtil.putDataProperty(ctx, prototype, JSObject.CONSTRUCTOR, constructor, JSAttributes.configurableNotEnumerableNotWritable());
        JSObjectUtil.putDataProperty(ctx, prototype, Symbol.SYMBOL_TO_STRING_TAG, ASYNC_GENERATOR_NAME, JSAttributes.configurableNotEnumerableNotWritable());
        return prototype;
    }

    public static JSConstructor createAsyncGeneratorFunctionConstructor(JSRealm realm) {
        JSContext ctx = realm.getContext();
        // intrinsic constructor %AsyncGeneratorFunction%
        DynamicObject constructor = realm.lookupFunction(ConstructorBuiltins.BUILTINS, ASYNC_GENERATOR_FUNCTION_NAME);
        JSObject.setPrototype(constructor, realm.getFunctionConstructor());
        DynamicObject prototype = createAsyncGeneratorFunctionPrototype(realm, constructor);
        JSObjectUtil.putDataProperty(ctx, constructor, JSObject.PROTOTYPE, prototype, JSAttributes.notConfigurableNotEnumerableNotWritable());
        return new JSConstructor(constructor, prototype);
    }

    // ##### Bound functions and enumerate iterator

    public static DynamicObject createEnumerateIteratorPrototype(JSRealm realm) {
        DynamicObject iteratorPrototype = realm.getIteratorPrototype();
        DynamicObject enumerateIteratorPrototype = JSObject.createInit(realm, iteratorPrototype, JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(realm, enumerateIteratorPrototype, EnumerateIteratorPrototypeBuiltins.BUILTINS);
        return enumerateIteratorPrototype;
    }

    public static Shape makeInitialEnumerateIteratorShape(JSContext context, DynamicObject enumerateIteratorPrototype) {
        final Property iteratorProperty = JSObjectUtil.makeHiddenProperty(JSRuntime.ENUMERATE_ITERATOR_ID,
                        JSShape.makeAllocator(JSObject.LAYOUT).locationForType(Iterator.class, EnumSet.of(LocationModifier.Final, LocationModifier.NonNull)));
        return JSObjectUtil.getProtoChildShape(enumerateIteratorPrototype, JSUserObject.INSTANCE, context).addProperty(iteratorProperty);
    }

    public static DynamicObject createForInIteratorPrototype(JSRealm realm) {
        DynamicObject iteratorPrototype = realm.getIteratorPrototype();
        DynamicObject enumerateIteratorPrototype = JSObject.createInit(realm, iteratorPrototype, JSUserObject.INSTANCE);
        JSObjectUtil.putFunctionsFromContainer(realm, enumerateIteratorPrototype, ForInIteratorPrototypeBuiltins.BUILTINS);
        return enumerateIteratorPrototype;
    }

    public static Shape makeInitialForInIteratorShape(JSContext context, DynamicObject iteratorPrototype) {
        final Property iteratorProperty = JSObjectUtil.makeHiddenProperty(JSRuntime.FOR_IN_ITERATOR_ID,
                        JSShape.makeAllocator(JSObject.LAYOUT).locationForType(ForInIterator.class));
        return JSObjectUtil.getProtoChildShape(iteratorPrototype, JSUserObject.INSTANCE, context).addProperty(iteratorProperty);
    }

    public static Shape makeInitialBoundFunctionShape(JSContext context, DynamicObject prototype) {
        Shape initialShape = makeBaseFunctionShape(context, prototype);
        initialShape = initialShape.addProperty(BOUND_TARGET_FUNCTION_PROPERTY);
        initialShape = initialShape.addProperty(BOUND_THIS_PROPERTY);
        initialShape = initialShape.addProperty(BOUND_ARGUMENTS_PROPERTY);
        initialShape = addLengthProxyProperty(initialShape, context);
        initialShape = addNameProxyProperty(initialShape);
        initialShape = addCallerAndArgumentsProperties(initialShape, context, true);
        return initialShape;
    }

    private static RootNode getFrameRootNode(FrameInstance frameInstance) {
        Node callNode = frameInstance.getCallNode();
        if (callNode != null) {
            return callNode.getRootNode();
        }
        CallTarget callTarget = frameInstance.getCallTarget();
        if (callTarget instanceof RootCallTarget) {
            return ((RootCallTarget) callTarget).getRootNode();
        }
        return null;
    }

    public static SourceSection createBuiltinSourceSection(String name) {
        return Source.newBuilder(JavaScriptLanguage.ID, "", name).internal(true).build().createUnavailableSection();
    }

    public static boolean isBuiltinSourceSection(SourceSection sourceSection) {
        return sourceSection == BUILTIN_SOURCE_SECTION;
    }

    public static boolean isBuiltinThatShouldNotAppearInStackTrace(JSRealm realm, DynamicObject function) {
        return function == realm.getApplyFunctionObject() || function == realm.getCallFunctionObject() || function == realm.getReflectApplyFunctionObject() ||
                        function == realm.getReflectConstructFunctionObject();
    }

    private static class ArgumentsProxyProperty implements PropertyProxy {

        private final JSContext context;

        ArgumentsProxyProperty(JSContext context) {
            this.context = context;
        }

        @Override
        public Object get(DynamicObject thiz) {
            if (context.isOptionV8CompatibilityMode()) {
                return JSRuntime.toJSNull(createArguments(thiz));
            } else {
                return Undefined.instance;
            }
        }

        @TruffleBoundary
        private static Object createArguments(DynamicObject thiz) {
            return Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Object>() {
                @Override
                public Object visitFrame(FrameInstance frameInstance) {
                    RootNode rootNode = getFrameRootNode(frameInstance);
                    if (JSRuntime.isJSFunctionRootNode(rootNode)) {
                        Frame frame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_WRITE);
                        DynamicObject function = (DynamicObject) JSArguments.getFunctionObject(frame.getArguments());
                        if (function == thiz) {
                            JSFunctionData functionData = JSFunction.getFunctionData(function);
                            JSContext context = functionData.getContext();
                            JSRealm realm = context.getRealm();
                            Object[] userArguments = JSArguments.extractUserArguments(frame.getArguments());
                            return JSArgumentsObject.createNonStrict(context, realm, userArguments, function);
                        }
                    }
                    return null;
                }
            });
        }

    }

    private static class CallerProxyProperty implements PropertyProxy {

        private final JSContext context;

        CallerProxyProperty(JSContext context) {
            this.context = context;
        }

        @Override
        public Object get(DynamicObject thiz) {
            if (context.isOptionV8CompatibilityMode()) {
                return JSRuntime.toJSNull(findCaller(thiz));
            } else {
                return Undefined.instance;
            }
        }

        @TruffleBoundary
        private static Object findCaller(DynamicObject thiz) {
            return Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Object>() {
                private boolean seenThis = false;

                @Override
                public Object visitFrame(FrameInstance frameInstance) {
                    RootNode rootNode = getFrameRootNode(frameInstance);
                    if (JSRuntime.isJSFunctionRootNode(rootNode)) {
                        Frame frame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_WRITE);
                        DynamicObject function = (DynamicObject) JSArguments.getFunctionObject(frame.getArguments());
                        if (seenThis) {
                            SourceSection ss = rootNode.getSourceSection();
                            if (ss == null) {
                                return null;
                            }
                            if (ss.getSource().isInternal() && !JSFunction.isBuiltinSourceSection(ss)) {
                                return null;
                            }
                            String sourceName = ss.getSource().getName();
                            if (Evaluator.EVAL_SOURCE_NAME.equals(sourceName) || sourceName.startsWith(Evaluator.EVAL_AT_SOURCE_NAME_PREFIX)) {
                                return null; // skip eval()
                            }
                            JSFunctionData functionData = JSFunction.getFunctionData(function);
                            if (JSFunction.isBuiltinSourceSection(ss)) {
                                JSRealm realm = functionData.getContext().getRealm();
                                if (isBuiltinThatShouldNotAppearInStackTrace(realm, function)) {
                                    return null;
                                }
                                if (functionData.getName().startsWith("[Symbol.")) {
                                    return null;
                                }
                                if (isStrictBuiltin(function)) {
                                    return Null.instance; // do not go beyond a strict builtin
                                }
                            } else if (functionData.isStrict()) {
                                return Null.instance;
                            }
                            if (!PROGRAM_FUNCTION_NAME.equals(rootNode.getName())) {
                                return function;
                            }
                        } else if (function == thiz) {
                            seenThis = true;
                        }
                    }
                    return null;
                }
            });
        }
    }

    // V8 distinguishes strict and sloppy builtins, see mjsunit/function-caller.js
    public static boolean isStrictBuiltin(DynamicObject function) {
        JSFunctionData functionData = JSFunction.getFunctionData(function);
        JSRealm realm = functionData.getContext().getRealm();
        PropertyDescriptor desc = JSObject.getOwnProperty(realm.getArrayPrototype(), functionData.getName());
        return desc != null && desc.isDataDescriptor() && desc.getValue() == function;
    }
}

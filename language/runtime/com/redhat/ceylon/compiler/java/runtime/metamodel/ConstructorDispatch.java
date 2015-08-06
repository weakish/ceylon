package com.redhat.ceylon.compiler.java.runtime.metamodel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import com.redhat.ceylon.compiler.java.Util;
import com.redhat.ceylon.compiler.java.codegen.Decl;
import com.redhat.ceylon.compiler.java.metadata.Ignore;
import com.redhat.ceylon.compiler.java.metadata.Name;
import com.redhat.ceylon.model.typechecker.model.Declaration;
import com.redhat.ceylon.model.typechecker.model.Function;
import com.redhat.ceylon.model.typechecker.model.Parameter;
import com.redhat.ceylon.model.typechecker.model.Reference;

import ceylon.language.Array;
import ceylon.language.Callable;
import ceylon.language.Sequential;
import ceylon.language.empty_;
import ceylon.language.meta.model.InvocationException;

/**
 * Dispatches to constructors
 * 
 * @author tom
 * @param <Type>
 * @param <Arguments>
 */
class ConstructorDispatch<Type, Arguments extends Sequential<? extends Object>> 
    implements Callable<Type>, DefaultValueProvider  {

    private final FreeClass freeClass;
    private MethodHandle constructor;
    final int firstDefaulted;
    final int variadicIndex;
    private MethodHandle[] dispatch;
    final List<com.redhat.ceylon.model.typechecker.model.Type> parameterProducedTypes;
    final Sequential<? extends ceylon.language.meta.model.Type<? extends Object>> parameterTypes;
    final Object instance;
    private Reference constructorReference;// TODO get rid of this
    
    ConstructorDispatch(
            Reference constructorReference,
            AppliedClass<Type, ?> appliedClass,
            FreeCallableConstructor freeConstructor,
            List<Parameter> parameters, Object instance) {
        this.constructorReference = constructorReference;
        freeClass = (FreeClass)appliedClass.declaration;
        com.redhat.ceylon.model.typechecker.model.Class classDecl = (com.redhat.ceylon.model.typechecker.model.Class)appliedClass.declaration.declaration;
        //com.redhat.ceylon.model.typechecker.model.Constructor decl = freeConstructor.constructor;
        //List<Parameter> parameters = decl.getFirstParameterList().getParameters();
        this.firstDefaulted = Metamodel.getFirstDefaultedParameter(parameters);
        this.variadicIndex = Metamodel.getVariadicParameter(parameters);

        boolean invokeOnCompanionInstance = this.instance != null 
                && classDecl.getContainer() instanceof com.redhat.ceylon.model.typechecker.model.Interface
                && !classDecl.isShared();
        if (invokeOnCompanionInstance) {
            this.instance = Metamodel.getCompanionInstance(instance, (com.redhat.ceylon.model.typechecker.model.Interface)freeClass.declaration.getContainer());
        } else {
            this.instance = instance;
        }
        
        Member[] defaultedMethods = null;
        if(firstDefaulted != -1){
            // if we have 2 params and first is defaulted we need 2 + 1 - 0 = 3 methods:
            // f(), f(a) and f(a, b)
            this.dispatch = new MethodHandle[parameters.size() + 1 - firstDefaulted];
            defaultedMethods = new Member[dispatch.length];
        }

        // get a list of produced parameter types
        this.parameterProducedTypes = Metamodel.getParameterProducedTypes(parameters, constructorReference);
        this.parameterTypes = Metamodel.getAppliedMetamodelSequential(this.parameterProducedTypes);

        // FIXME: delay constructor setup for when we actually use it?
        // FIXME: finding the right MethodHandle for the constructor could actually be done in the Class declaration
        java.lang.Class<?> javaClass = Metamodel.getJavaClass(freeClass.declaration);
        // FIXME: faster lookup with types? but then we have to deal with erasure and stuff
        Member found = null;
        if (freeConstructor != null && Decl.isConstructor(freeConstructor.declaration)) {
            boolean variadic = false;// TODO
            java.lang.reflect.Constructor<?> ctor = Metamodel.getJavaConstructor(Decl.getConstructor(freeConstructor.declaration));
            constructor = reflectionToMethodHandle(constructorReference, 
                    ctor, 
                    javaClass, 
                    this.instance, 
                    parameterProducedTypes, 
                    variadic, 
                    false);
        } else {
            if(MethodHandleUtil.isJavaArray(javaClass)){
                found = MethodHandleUtil.setupArrayConstructor(javaClass, defaultedMethods);
            }else if(!javaClass.isMemberClass() 
                    || !Metamodel.isCeylon((com.redhat.ceylon.model.typechecker.model.Class)appliedClass.declaration.declaration)
                    // private ceylon member classes don't have any outer constructor method so treat them like java members
                    || !classDecl.isShared()){
                for(Constructor<?> constr : javaClass.getDeclaredConstructors()){
                    if (constr.isAnnotationPresent(Name.class)){
                        continue;
                    }
                    if(constr.isAnnotationPresent(Ignore.class)){
                        // it's likely an overloaded constructor
                        // FIXME: proper checks
                        if(firstDefaulted != -1){
                            Class<?>[] ptypes = constr.getParameterTypes();
                            if (ptypes.length > 0 && ptypes[0].equals(com.redhat.ceylon.compiler.java.runtime.serialization.$Serialization$.class)) {
                                // it was a serialization constructor, we're not interested in those.
                                continue;
                            }
                            int implicitParameterCount = 0;
                            if (MethodHandleUtil.isReifiedTypeSupported(constr, javaClass.isMemberClass())) { 
                                implicitParameterCount += classDecl.getTypeParameters().size();
                            }
                            if (classDecl.isClassMember() && javaClass.isMemberClass() 
                                    || classDecl.isInterfaceMember() && invokeOnCompanionInstance/*!declaration.constructor.isShared()*/) { 
                                // non-shared member classes don't get instantiators, so there's the 
                                // synthetic outerthis parameter to account for.
                                implicitParameterCount++;
                            }
                            // this doesn't need to count synthetic parameters because we only use the constructor for Java types
                            // which can't have defaulted parameters
                            int params = constr.getParameterTypes().length - implicitParameterCount;
                            defaultedMethods[params - firstDefaulted] = constr;
                        }
                        continue;
                    }
                    // FIXME: deal with private stuff?
                    if(found != null){
                        throw Metamodel.newModelError("More than one constructor found for: "+javaClass+", 1st: "+found+", 2nd: "+constr);
                    }
                    found = constr;
                }
            }else{
                String builderName = freeClass.getName() + "$new$";
                // FIXME: this probably doesn't work for local classes
                // FIXME: perhaps store and access the container class literal from an extra param of @Container?
                java.lang.Class<?> outerJavaClass = Metamodel.getJavaClass((Declaration) freeClass.declaration.getContainer());
                for(Method meth : outerJavaClass.getDeclaredMethods()){
                    // FIXME: we need a better way to look things up: they're all @Ignore...
    //                if(meth.isAnnotationPresent(Ignore.class))
    //                    continue;
                    if(!meth.getName().equals(builderName))
                        continue;
                    // FIXME: proper checks
                    if(firstDefaulted != -1){
                        int reifiedTypeParameterCount = MethodHandleUtil.isReifiedTypeSupported(meth, true) 
                                ? classDecl.getTypeParameters().size() : 0;
                        int params = meth.getParameterTypes().length - reifiedTypeParameterCount;
                        if(params != parameters.size()){
                            defaultedMethods[params - firstDefaulted] = meth;
                            continue;
                        }
                    }
    
                    // FIXME: deal with private stuff?
                    if(found != null){
                        throw Metamodel.newModelError("More than one constructor method found for: "+javaClass+", 1st: "+found+", 2nd: "+meth);
                    }
                    found = meth;
                }
            }
        }
        if(found != null){
            boolean variadic = MethodHandleUtil.isVariadicMethodOrConstructor(found);
            constructor = reflectionToMethodHandle(constructorReference, 
                    found, 
                    javaClass, this.instance,
                    parameterProducedTypes, 
                    variadic, 
                    false);
            if(defaultedMethods != null && !variadic){
                // this won't find the last one, but it's method
                int i=0;
                for(;i<defaultedMethods.length-1;i++){
                    if(defaultedMethods[i] == null)
                        throw Metamodel.newModelError("Missing defaulted constructor for "+ freeClass.getName()
                                +" with "+(i+firstDefaulted)+" parameters in "+javaClass);
                    dispatch[i] = reflectionToMethodHandle(constructorReference, 
                            defaultedMethods[i], 
                            javaClass, 
                            this.instance, 
                            parameterProducedTypes, 
                            variadic, 
                            false);
                }
                dispatch[i] = constructor;
            }else if(variadic){
                // variadic methods don't have defaulted parameters, but we will simulate one because our calling convention is that
                // we treat variadic methods as if the last parameter is optional
                // firstDefaulted and dispatch already set up because getFirstDefaultedParameter treats java variadics like ceylon variadics
                dispatch[0] = reflectionToMethodHandle(constructorReference, 
                        found, 
                        javaClass, 
                        this.instance,
                        parameterProducedTypes, 
                        variadic, 
                        true);
                dispatch[1] = constructor;
            }
        }
    }
    /*
     * This one came from AppliedClass
    private MethodHandle reflectionToMethodHandle(Member found, Class<?> javaClass,  
                                                  com.redhat.ceylon.model.typechecker.model.Type producedType,
                                                  List<com.redhat.ceylon.model.typechecker.model.Type> parameterProducedTypes,
                                                  boolean variadic, boolean bindVariadicParameterToEmptyArray) {
        MethodHandle constructor = null;
        java.lang.Class<?>[] parameterTypes;
        java.lang.Class<?> returnType;
        boolean isJavaArray = MethodHandleUtil.isJavaArray(javaClass);
        boolean isStatic = Modifier.isStatic(javaClass.getModifiers());
        try {
            if(found instanceof java.lang.reflect.Constructor){
                ((java.lang.reflect.Constructor<?>) found).setAccessible(true);
                constructor = MethodHandles.lookup().unreflectConstructor((java.lang.reflect.Constructor<?>)found);
                parameterTypes = ((java.lang.reflect.Constructor<?>)found).getParameterTypes();
                returnType = javaClass;
            }else{
                ((Method)found).setAccessible(true);
                constructor = MethodHandles.lookup().unreflect((Method) found);
                parameterTypes = ((java.lang.reflect.Method)found).getParameterTypes();
                returnType = ((java.lang.reflect.Method)found).getReturnType();
            }
        } catch (IllegalAccessException e) {
            throw Metamodel.newModelError("Problem getting a MH for constructor for: "+javaClass, e);
        }
        boolean isJavaMember = found instanceof java.lang.reflect.Constructor && instance != null && !isStatic;

        // box the return type, which is only necessary for default parameter methods, and not constructors
        constructor = MethodHandleUtil.boxReturnValue(constructor, returnType, producedType);

        // we need to cast to Object because this is what comes out when calling it in $call
        
        // if it's a java member we will be using the member constructor which has an extra synthetic parameter so we can't bind it
        // until we have casted it first
        if(isJavaMember)
            constructor = constructor.asType(MethodType.methodType(Object.class, parameterTypes));
        // now bind it to the object
        if(instance != null
                && (isJavaArray || !isStatic))
            constructor = constructor.bindTo(instance);
        // if it was not a java member we have no extra synthetic instance parameter and we need to get rid of it before casting
        if(!isJavaMember)
            constructor = constructor.asType(MethodType.methodType(Object.class, parameterTypes));
        
        int typeParametersCount = Util.toInt(this.freeClass.getTypeParameterDeclarations().getSize());
        int skipParameters = 0;
        if(isJavaMember)
            skipParameters++; // skip the first parameter for boxing
        // insert any required type descriptors
        if(typeParametersCount != 0 && MethodHandleUtil.isReifiedTypeSupported(found, isJavaMember)){
            List<com.redhat.ceylon.model.typechecker.model.Type> typeArguments = producedType.getTypeArgumentList();
            constructor = MethodHandleUtil.insertReifiedTypeArguments(constructor, 0, typeArguments);
            skipParameters += typeParametersCount;
        }
        // now convert all arguments (we may need to unbox)
        constructor = MethodHandleUtil.unboxArguments(constructor, skipParameters, 0, parameterTypes,
                                                      parameterProducedTypes, variadic, bindVariadicParameterToEmptyArray);
        
        return constructor;
    }*/
    
    private static MethodHandle reflectionToMethodHandle(
            Reference constructorReference,
            Member found, 
            java.lang.Class<?> javaClass, 
            Object instance, 
            List<com.redhat.ceylon.model.typechecker.model.Type> parameterProducedTypes,
            boolean variadic, 
            boolean bindVariadicParameterToEmptyArray) {
        // save the parameter types before we mess with "found"
        java.lang.Class<?>[] parameterTypes;
        java.lang.Class<?> returnType;
        MethodHandle method = null;
        boolean isJavaArray;
        int mods;
        int typeParametersCount;
        int skipParameters = 0;
        List<com.redhat.ceylon.model.typechecker.model.TypeParameter> reifiedTypeParameters;
        com.redhat.ceylon.model.typechecker.model.Constructor constructorModel;
        if (found instanceof java.lang.reflect.Method) {
            com.redhat.ceylon.model.typechecker.model.Function functionModel = (com.redhat.ceylon.model.typechecker.model.Function)constructorReference.getDeclaration();
            java.lang.reflect.Method foundMethod = (java.lang.reflect.Method)found;
            parameterTypes = foundMethod.getParameterTypes();
            returnType = foundMethod.getReturnType();
            mods = foundMethod.getModifiers();
            isJavaArray = MethodHandleUtil.isJavaArray(javaClass);
            typeParametersCount = foundMethod.getTypeParameters().length;
            try {
                if(isJavaArray){
                    if(foundMethod.getName().equals("get"))
                        method = MethodHandleUtil.getJavaArrayGetterMethodHandle(javaClass);
                    else if(foundMethod.getName().equals("set"))
                        method = MethodHandleUtil.getJavaArraySetterMethodHandle(javaClass);
                    else if(foundMethod.getName().equals("copyTo")){
                        found = MethodHandleUtil.getJavaArrayCopyToMethod(javaClass, foundMethod);
                    }
                }
                if(method == null){
                    foundMethod.setAccessible(true);
                    method = MethodHandles.lookup().unreflect(foundMethod);
                }
            } catch (IllegalAccessException e) {
                throw Metamodel.newModelError("Problem getting a MH for constructor for: "+javaClass, e);
            }
            reifiedTypeParameters = functionModel.getTypeParameters();
            constructorModel = null;
        } else if (found instanceof java.lang.reflect.Constructor<?>) {
            com.redhat.ceylon.model.typechecker.model.Declaration functionOrConstructorModel = constructorReference.getDeclaration();
            java.lang.reflect.Constructor<?> foundMethod = (java.lang.reflect.Constructor<?>)found;
            parameterTypes = foundMethod.getParameterTypes();
            returnType = javaClass;
            mods = foundMethod.getModifiers();
            foundMethod.setAccessible(true);
            try {
                method = MethodHandles.lookup().unreflectConstructor(foundMethod);
            } catch (IllegalAccessException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            isJavaArray = false;
            typeParametersCount = javaClass.getTypeParameters().length;
            if (functionOrConstructorModel instanceof com.redhat.ceylon.model.typechecker.model.Constructor) {
                constructorModel = (com.redhat.ceylon.model.typechecker.model.Constructor)functionOrConstructorModel;
                reifiedTypeParameters = Decl.getConstructedClass(constructorModel).getTypeParameters();
            } else if (functionOrConstructorModel instanceof Function) {
                constructorModel = (com.redhat.ceylon.model.typechecker.model.Constructor)((Function)functionOrConstructorModel).getTypeDeclaration();
                reifiedTypeParameters = Decl.getConstructedClass(constructorModel).getTypeParameters();
            } else if (functionOrConstructorModel instanceof com.redhat.ceylon.model.typechecker.model.Class) {
                constructorModel = null;
                reifiedTypeParameters = ((com.redhat.ceylon.model.typechecker.model.Class)functionOrConstructorModel).getTypeParameters();
            } else {
                throw new RuntimeException();
            }
        } else {
            throw new RuntimeException();
        }

        // box the return type
        method = MethodHandleUtil.boxReturnValue(method, returnType, constructorReference.getType());
        // we need to cast to Object because this is what comes out when calling it in $call
        if(instance != null 
                && (isJavaArray || !Modifier.isStatic(mods)))
            method = method.bindTo(instance);
        method = method.asType(MethodType.methodType(Object.class, parameterTypes));
        // insert any required type descriptors
        if(typeParametersCount != 0 && MethodHandleUtil.isReifiedTypeSupported(found, false)){
            List<com.redhat.ceylon.model.typechecker.model.Type> typeArguments = new ArrayList<com.redhat.ceylon.model.typechecker.model.Type>();
            java.util.Map<com.redhat.ceylon.model.typechecker.model.TypeParameter, com.redhat.ceylon.model.typechecker.model.Type> typeArgumentMap = constructorReference.getQualifyingType().getTypeArguments();
            for (com.redhat.ceylon.model.typechecker.model.TypeParameter tp : reifiedTypeParameters) {
                typeArguments.add(typeArgumentMap.get(tp));
            }
            method = MethodHandleUtil.insertReifiedTypeArguments(method, 0, typeArguments);
            skipParameters += typeParametersCount;
        }
        // Now, if it's a constructor we need to insert the (null) constructor name argument
        if (constructorModel != null
                && constructorModel.getName() != null) {
            method = MethodHandleUtil.insertConstructorNameArgument(method, 0, constructorModel);
            skipParameters += 1;
        }
        // now convert all arguments (we may need to unbox)
        method = MethodHandleUtil.unboxArguments(method, skipParameters, 0, parameterTypes,
                parameterProducedTypes, variadic, bindVariadicParameterToEmptyArray);
        return method;
    }
    
    ceylon.language.meta.model.Constructor<Type, Sequential<? extends Object>> checkConstructor() {
        if(((FreeClass)freeClass).getAbstract())
            throw new InvocationException("Abstract class cannot be instantiated");
        if(((FreeClass)freeClass).getAnonymous())
            throw new InvocationException("Object class cannot be instantiated");
        if(constructor == null)
            throw Metamodel.newModelError("No constructor found for: "+freeClass.getName());
        return null;
    }
    
    @Override
    public Type $call$() {
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact();
            // FIXME: proper checks
            return (Type)dispatch[0].invokeExact();
        } catch (Throwable e) {
            Util.rethrow(e);
            return null;
        }
    }
    
    @Override
    public Type $callvariadic$() {
        return $callvariadic$(empty_.get_());
    }
    
    @Override
    public Type $callvariadic$(Sequential<?> varargs) {
        return $call$(varargs);
    }
    
    @Override
    public Type $call$(Object arg0) {
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(arg0);
            // FIXME: proper checks
            return (Type)dispatch[1-firstDefaulted].invokeExact(arg0);
        } catch (Throwable e) {
            Util.rethrow(e);
            return null;
        }
    }
    
    @Override
    public Type $callvariadic$(Object arg0) {
        return $callvariadic$(arg0, empty_.get_());
    }
    
    @Override
    public Type $callvariadic$(Object arg0, Sequential<?> varargs) {
        return $call$(arg0, varargs);
    }
    
    @Override
    public Type $call$(Object arg0, Object arg1) {
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(arg0, arg1);
            // FIXME: proper checks
            return (Type)dispatch[1-firstDefaulted].invokeExact(arg0, arg1);
        } catch (Throwable e) {
            Util.rethrow(e);
            return null;
        }
    }
    
    @Override
    public Type $callvariadic$(Object arg0, Object arg1) {
        return $callvariadic$(arg0, arg1, empty_.get_());
    }
    
    @Override
    public Type $callvariadic$(Object arg0, Object arg1, Sequential<?> varargs) {
        return $call$(arg0, arg1, varargs);
    }
    
    @Override
    public Type $call$(Object arg0, Object arg1, Object arg2) {
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(arg0, arg1, arg2);
            // FIXME: proper checks
            return (Type)dispatch[1-firstDefaulted].invokeExact(arg0, arg1, arg2);
        } catch (Throwable e) {
            Util.rethrow(e);
            return null;
        }
    }
    
    @Override
    public Type $callvariadic$(Object arg0, Object arg1, Object arg2) {
        return $callvariadic$(arg0, arg1, arg2, empty_.get_());
    }
    
    @Override
    public Type $callvariadic$(Object arg0, Object arg1, Object arg2,
            Sequential<?> varargs) {
        return $call$(arg0, arg1, arg2, varargs);
    }
    
    @Override
    public Type $call$(Object... args) {
        try {
            if(firstDefaulted == -1)
                return (Type)constructor.invokeExact(args);
            // FIXME: proper checks
            return (Type)dispatch[1-firstDefaulted].invokeExact(args);
        } catch (Throwable e) {
            Util.rethrow(e);
            return null;
        }
    }
    
    @Override
    public Type $callvariadic$(Object... argsAndVarargs) {
        return $call$(argsAndVarargs);
    }
    
    @Override
    public short $getVariadicParameterIndex$() {
        return (short)variadicIndex;
    }
    
    public ceylon.language.Sequential<? extends ceylon.language.meta.model.Type<? extends Object>> getParameterTypes(){
        return parameterTypes;
    }

    public List<com.redhat.ceylon.model.typechecker.model.Type> getProducedParameterTypes() {
        return parameterProducedTypes;
    }
    
    
    @Override
    public Object getDefaultParameterValue(Parameter parameter, Array<Object> values, int collectedValueCount) {
        com.redhat.ceylon.model.typechecker.model.Class decl = 
                (com.redhat.ceylon.model.typechecker.model.Class)freeClass.declaration;
        java.lang.Class<?> javaClass = Metamodel.getJavaClass(decl);

        Method found = null;
        String name;
        java.lang.Class<?> lookupClass;
        if(!javaClass.isMemberClass()){
            name = "$default$"+parameter.getName();
            lookupClass = javaClass;
        }else{
            name = "$default$" + freeClass.getName() + "$" + parameter.getName();
            // FIXME: perhaps store and access the container class literal from an extra param of @Container?
            lookupClass = Metamodel.getJavaClass((Declaration) freeClass.declaration.getContainer());
        }
        // iterate to find it, rather than figure out its parameter types
        for(Method m : lookupClass.getDeclaredMethods()){
            if(m.getName().equals(name)){
                found = m;
                break;
            }
        }
        if(found == null)
            throw Metamodel.newModelError("Default argument method for "+parameter.getName()+" not found");
        int parameterCount = found.getParameterTypes().length;
        if(MethodHandleUtil.isReifiedTypeSupported(found, false))
            parameterCount -= found.getTypeParameters().length;
        if(parameterCount != collectedValueCount)
            throw Metamodel.newModelError("Default argument method for "+parameter.getName()+" requires wrong number of parameters: "+parameterCount+" should be "+collectedValueCount);
        
        // AFAIK default value methods cannot be Java-variadic 
        MethodHandle methodHandle = reflectionToMethodHandle(constructorReference, found, javaClass, constructorReference.getType(), parameterProducedTypes, false, false);
        // sucks that we have to copy the array, but that's the MH API
        java.lang.Object[] arguments = new java.lang.Object[collectedValueCount];
        System.arraycopy(values.toArray(), 0, arguments, 0, collectedValueCount);
        try {
            return methodHandle.invokeWithArguments(arguments);
        } catch (Throwable e) {
            Util.rethrow(e);
            return null;
        }
    }
}

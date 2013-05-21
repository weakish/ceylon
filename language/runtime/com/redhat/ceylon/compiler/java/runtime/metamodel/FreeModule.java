package com.redhat.ceylon.compiler.java.runtime.metamodel;

import java.util.List;

import ceylon.language.SequenceBuilder;
import ceylon.language.Sequential;
import ceylon.language.metamodel.Annotated$impl;
import ceylon.language.metamodel.untyped.Module$impl;

import com.redhat.ceylon.compiler.java.Util;
import com.redhat.ceylon.compiler.java.metadata.Ignore;
import com.redhat.ceylon.compiler.java.metadata.TypeInfo;
import com.redhat.ceylon.compiler.java.runtime.model.ReifiedType;
import com.redhat.ceylon.compiler.java.runtime.model.TypeDescriptor;
import com.redhat.ceylon.compiler.typechecker.model.ModuleImport;

public class FreeModule implements ceylon.language.metamodel.untyped.Module,
        ceylon.language.metamodel.Annotated, AnnotationBearing,
        ReifiedType {

    @Ignore
    public static final TypeDescriptor $TypeDescriptor = TypeDescriptor.klass(FreeModule.class);
    protected com.redhat.ceylon.compiler.typechecker.model.Module declaration;
    private Sequential<FreePackage> packages;
    private Sequential<FreeImport> dependencies;
    
    public FreeModule(com.redhat.ceylon.compiler.typechecker.model.Module declaration) {
        this.declaration = declaration;
    }

    @Override
    @Ignore
    public Module$impl $ceylon$language$metamodel$untyped$Module$impl() {
        // TODO Auto-generated method stub
        return null;
    }
    
    @Override
    @Ignore
    public Annotated$impl $ceylon$language$metamodel$Annotated$impl() {
        // TODO Auto-generated method stub
        return null;
    }
    
    @Override
    @Ignore
    public java.lang.annotation.Annotation[] $getJavaAnnotations() {
        return Metamodel.getJavaClass(declaration).getAnnotations();
    }

    @Override
    @TypeInfo("ceylon.language::Sequential<ceylon.language.metamodel.untyped::Package>")
    public Sequential<? extends FreePackage> getMembers() {
        // no need to synchronise as concurrent invocations should get the same array back
        if(this.packages == null){
            List<com.redhat.ceylon.compiler.typechecker.model.Package> modelPackages = declaration.getAllPackages();
            FreePackage[] packages = new FreePackage[modelPackages.size()];
            for(int i=0;i<packages.length;i++){
                packages[i] = Metamodel.getOrCreateMetamodel(modelPackages.get(i));
            }
            this.packages = Util.sequentialInstance(FreePackage.$TypeDescriptor, packages);
        }
        return this.packages;
    }
    
    @Override
    @TypeInfo("ceylon.language::Sequential<ceylon.language.metamodel.untyped::Import>")
    public Sequential<? extends FreeImport> getDependencies() {
        // no need to synchronise as concurrent invocations should get the same array back
        if(this.dependencies == null){
            List<com.redhat.ceylon.compiler.typechecker.model.ModuleImport> modelImports = declaration.getImports();
            //FreeImport[] imports = new FreeImport[modelImports.size()];
            SequenceBuilder sb = new SequenceBuilder<>(FreeImport.$TypeDescriptor, modelImports.size()-1);
            for(com.redhat.ceylon.compiler.typechecker.model.ModuleImport moduleImport : modelImports){
                if ("ceylon.language".equals(moduleImport.getModule().getNameAsString())) {
                    continue;
                }
                sb.append(new FreeImport(this, moduleImport));
            }
            this.dependencies = sb.getSequence();
        }
        return this.dependencies;
    }

    @Override
    @TypeInfo("ceylon.language::String")
    public String getName() {
        return declaration.getNameAsString();
    }

    @Override
    @TypeInfo("ceylon.language::String")
    public String getVersion() {
        return declaration.getVersion();
    }

    @Ignore
    @Override
    public TypeDescriptor $getType() {
        return $TypeDescriptor;
    }
}

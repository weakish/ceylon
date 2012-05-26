package com.redhat.ceylon.compiler.typechecker.model;

public class DeclarationWithProximity {
    private Declaration declaration;
    private int proximity;
    private String name;
    private NamedArgumentList namedArgumentList; 
    
    public NamedArgumentList getNamedArgumentList() {
        return namedArgumentList;
    }
    
    public DeclarationWithProximity(Parameter parameter, NamedArgumentList nal) {
        this.declaration = parameter;
        this.proximity = 0;
        this.name = declaration.getName();
        this.namedArgumentList = nal;
    }
    
    public DeclarationWithProximity(Declaration declaration, int proximity) {
        this.declaration = declaration;
        this.proximity = proximity;
        this.name = declaration.getName();
    }
    
    public DeclarationWithProximity(Declaration declaration, DeclarationWithProximity dwp) {
        this.declaration = declaration;
        this.proximity = dwp.proximity;
        this.name = dwp.name;
    }
    
    public DeclarationWithProximity(Import imp, int proximity) {
        this.declaration = imp.getDeclaration();
        this.proximity = proximity;
        this.name = imp.getAlias();
    }
    
    public Declaration getDeclaration() {
        return declaration;
    }
    
    public int getProximity() {
        return proximity;
    }
    
    public String getName() {
        return name;
    }
    
    @Override
    public String toString() {
        return name + ":" + declaration.toString() + "@" + proximity;
    }
    
}

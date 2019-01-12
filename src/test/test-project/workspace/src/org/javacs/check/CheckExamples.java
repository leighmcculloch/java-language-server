package org.javacs.check;

class CheckExamples {
    void localVar() {
        var x = 1;
        
    }

    void parameter(int param) {

    }

    void memberSelect(HasField param) {
        
    }

    int intMethod() {
        return 1;
    }

    void callMethod() {
        
    }

    void callMemberMethod(CheckExamples param) {
        
    }

    int[] arrayField;

    void checkArrayField() {

    }

    void conditionalExpr() {
        var cond = false;
        var ifTrue = 1;
        var ifFalse = 2;
        
    }

    int anonymousClass() {
        return (new Object(){ int foo; int bar; }).foo;
    }
}

class HasField {
    int field;
}
package src.org.javacs.interpreter;

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
}

class HasField {
    int field;
}
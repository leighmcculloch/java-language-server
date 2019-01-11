package org.javacs.check;

class CheckTricky {
    int anonymousClass() {
        return (new Object(){ int foo; int bar; }).bar;
    }
}
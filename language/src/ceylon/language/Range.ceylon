shared sealed abstract class Range<Element>()
        extends Object()  
        satisfies [Element+]
        given Element satisfies Enumerable<Element> {
    
    shared formal Boolean containsElement(Element x);
    shared formal Range<Element> shifted(Integer shift);
    
    shared formal Boolean increasing;
    shared formal Boolean decreasing;
    
    //TODO: includesRange()
}
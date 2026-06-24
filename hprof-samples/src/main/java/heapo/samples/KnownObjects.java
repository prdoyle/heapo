package heapo.samples;

public class KnownObjects {
    // Hold references statically to prevent GC collection before dump
    public static Object foo1, foo2, bar1;
    public static Object deep1;
    public static Object named1, named2;

    public static void allocate() {
        Foo f1 = new Foo(42);
        Foo f2 = new Foo(99);
        Bar b  = new Bar(7, f1);
        f1.next = f2;
        foo1 = f1;
        foo2 = f2;
        bar1 = b;

        DeepDerived d = new DeepDerived();
        d.baseRef   = new Object();
        d.baseInt   = 333;
        d.middleRef = new Object();
        d.middleInt = 222;
        d.derivedRef = new Object();
        d.derivedInt = 111;
        deep1 = d;

        named1 = new Named("hello world");
        named2 = new Named("goodbye cruel world");
    }

    public static class Foo {
        public Foo next;
        public int size;

        public Foo(int size) { this.size = size; }
    }

    public static class Bar {
        public Foo owner;
        public int count;

        public Bar(int count, Foo owner) { this.count = count; this.owner = owner; }
    }

    public static class DeepBase {
        public Object baseRef;
        public int    baseInt;
    }

    public static class DeepMiddle extends DeepBase {
        public Object middleRef;
        public int    middleInt;
    }

    public static class DeepDerived extends DeepMiddle {
        public Object derivedRef;
        public int    derivedInt;
    }

    public static class Named {
        public String name;
        public Named(String name) { this.name = name; }
    }
}

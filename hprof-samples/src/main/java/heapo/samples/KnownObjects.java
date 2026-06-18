package heapo.samples;

public class KnownObjects {
    // Hold references statically to prevent GC collection before dump
    public static Object foo1, foo2, bar1;

    public static void allocate() {
        foo1 = new Foo();
        foo2 = new Foo();
        bar1 = new Bar();
        ((Foo) foo1).next = (Foo) foo2;
        ((Bar) bar1).owner = (Foo) foo1;
    }

    public static class Foo {
        public Foo next;
    }

    public static class Bar {
        public Foo owner;
    }
}

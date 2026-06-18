package heapo.samples;

public class KnownObjects {
    // Hold references statically to prevent GC collection before dump
    public static Object foo1, foo2, bar1;

    public static void allocate() {
        Foo f1 = new Foo(42);
        Foo f2 = new Foo(99);
        Bar b  = new Bar(7, f1);
        f1.next = f2;
        foo1 = f1;
        foo2 = f2;
        bar1 = b;
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
}

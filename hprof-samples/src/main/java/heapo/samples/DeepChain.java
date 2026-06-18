package heapo.samples;

public class DeepChain {
    public static Object head;

    private static final int CHAIN_LENGTH = 100_000;

    public static void allocate() {
        Node prev = null;
        for (int i = 0; i < CHAIN_LENGTH; i++) {
            Node n = new Node();
            n.next = prev;
            prev = n;
        }
        head = prev;
    }

    public static class Node {
        public Node next;
    }
}

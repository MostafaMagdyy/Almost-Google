package SearchEngine;

/*
    Crawler is the consumer
    so there must be a producer
 */


import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoClient;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;

import static com.mongodb.client.MongoClients.create;

//


class CrawlerStore {
    public static int MAX_SIZE = 40;
    private final Queue<String> queue = new LinkedList<>();
    private final Hashtable<Integer, Boolean> rec = new Hashtable<>();
    private final MongoCollection<org.bson.Document> queueCollection;
    private final MongoCollection<org.bson.Document> visitedCollection;
    private boolean readSeed;

    CrawlerStore(MongoCollection<org.bson.Document> qc, MongoCollection<org.bson.Document> vc) {
        queueCollection = qc;
        visitedCollection = vc;
        readSeed = false;
    }

    private static ArrayList<String> readSeeds() {
        ArrayList<String> seeds = new ArrayList<>();
        try {
            File seedFile = new File("/home/walid/vsCode/Almost-Google/backend/src/main/java/SearchEngine/seed.txt");
            Scanner seedFileScanner = new Scanner(seedFile);
            while (seedFileScanner.hasNextLine()) {
                String link = seedFileScanner.nextLine();
                seeds.add(link);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return seeds;
    }

    public void fillQueue() {
        var seeds = readSeeds();
        if (!readSeed && visitedCollection.countDocuments(new org.bson.Document().append("url", seeds.get(0))) == 0)
            synchronized (this) {
                queue.addAll(seeds);
                System.out.println("======================================= Initial Fill ===========================");
            }
        else {
            readSeed = true;
            FindIterable<org.bson.Document> documentCursor = queueCollection.find().sort(new org.bson.Document()
                    .append("_id", 1)).limit(MAX_SIZE);
            var urlQueue = new ArrayList<String>();
            for (org.bson.Document document : documentCursor) {
                urlQueue.add((String) document.get("url"));
                queueCollection.deleteOne(new org.bson.Document().append("url", document.get("url")));
            }
            synchronized (this) {
                queue.addAll(urlQueue);
            }
        }
    }

    public void addToQueueCollection(ArrayList<org.bson.Document> urls) {
        synchronized (queueCollection) {
            queueCollection.insertMany(urls);
        }
    }

    public String dequeueUrl() {
        synchronized (this) {
            String result = queue.poll();
            while (rec.containsKey(result.hashCode()) &&
                    visitedCollection.countDocuments(new org.bson.Document().append("url", result.hashCode())) != 0)
                result = queue.poll();
            return result;
        }
    }

    public void addToVisited(org.bson.Document url) {
        synchronized (this) {
            visitedCollection.insertOne(url);
            rec.put(url.get("url").hashCode(), true);
        }
    }

    public boolean isQueueEmpty() {
        return queue.isEmpty();
    }

    public boolean doesDocumentExist(String url, String compactString) {
        return
                rec.containsKey(url.hashCode()) ||
                        visitedCollection.countDocuments(new org.bson.Document().append("url", url.hashCode())) > 0 ||
                        visitedCollection.countDocuments(new org.bson.Document().append("compactString", compactString)) > 0;
    }

    public int queueSize() {
        return queue.size();
    }
}

class Consumer implements Runnable {
    private static String cwd;
    private static CrawlerStore store;


    Consumer(CrawlerStore cs) {
        cwd = Paths.get("").toAbsolutePath().toString();
        store = cs;
        crawl();
    }

    @Override
    public void run() {
        crawl();
    }

    private void crawl() {
        while (true) {
            if (store.isQueueEmpty()) {
                try {
                    synchronized (store) {
                        store.notifyAll();
                        store.wait();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            String pageLink = store.dequeueUrl();
            Document doc;
            if ((doc = requestPage(pageLink)) == null)
                continue;
            String compactString = createCompactString(doc);
            if (!store.doesDocumentExist(pageLink, compactString)) {

                store.addToVisited(new org.bson.Document()
                        .append("compactString", compactString)
                        .append("url", pageLink));

                var linksCrawled = new ArrayList<org.bson.Document>();

                for (var link : doc.select("a[href]")) {
                    String newLinkStr = link.absUrl("href");
                    linksCrawled.add(new org.bson.Document()
                            .append("url", newLinkStr));
                }
                store.addToQueueCollection(linksCrawled);

                String JsonDocument = (new org.bson.Document()
                        .append("url", pageLink)
                        .append("document", doc.toString())).toJson();
                storeHTMLOnDisk(pageLink, JsonDocument);


            }
        }
    }

    private static void storeHTMLOnDisk(String pageLink, String JsonDocument) {
        try {
            String name = pageLink;
            name = name.replace("*", "`{}");
            name = name.replace("://", "}");
            name = name.replace("/", "{");
            name = name.replace("?", "`");
            File file = new File(cwd + "/Documents/" + name + ".json");
            System.out.println(file.getName());
            file.createNewFile();
            FileWriter writer = new FileWriter(file);
            writer.write(JsonDocument);
            writer.close();
            System.out.println("Link " + pageLink + " done");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Document requestPage(String page) {
        try {
            Connection connection = Jsoup.connect(page);
            Document doc = connection.get();
            if (connection.response().statusMessage().equals("OK"))
                return doc;
            else
                return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String createCompactString(Document doc) {

        String[] htmlParagraphTags = {"p", "h1", "h2", "h3", "a", "div"};
        StringBuilder CompactString = new StringBuilder();
        for (var tag : htmlParagraphTags) {
            for (var element : doc.select(tag)) {
                String text = element.ownText();
                int i = text.length() / 2;
                while (text.length() > 0 && i < text.length() && text.charAt(i) == ' ') {
                    i += 1;
                }
                if (i < text.length())
                    CompactString.append(text.charAt(i));
            }
        }
        return CompactString.substring(CompactString.length() / 2);
    }

}

class Producer implements Runnable {
    CrawlerStore store;

    Producer(CrawlerStore cs) {
        store = cs;
    }

    @Override
    public void run() {
        store.fillQueue();
        synchronized (store) {
            store.notifyAll();
        }
        produce();
    }

    private void produce() {
        while (true) {
            synchronized (store) {
                if (store.queueSize() == 0) {
                    store.fillQueue();
                    store.notifyAll();
                }
                try {
                    store.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

public class Crawler {
    private static MongoDatabase database;
    private static MongoCollection<org.bson.Document> visitedCollection;
    private static MongoCollection<org.bson.Document> queueCollection;
    private static CrawlerStore store;

    public static void main(String[] args) {
        initDatabase();
        store = new CrawlerStore(queueCollection, visitedCollection);
        (new Thread(new Producer(store), "p1")).start();
        (new Thread(new Consumer(store), "c1")).start();
        (new Thread(new Consumer(store), "c2")).start();
        while (true) {

        }
    }

    private static void initDatabase() {
        MongoClient mongoClient = create("mongodb://127.0.0.1:27017");
        database = mongoClient.getDatabase("test");
        visitedCollection = database.getCollection("visited");
        queueCollection = database.getCollection("queue");
    }
}
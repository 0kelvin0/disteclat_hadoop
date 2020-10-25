package distEclat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

public class ItemReaderMapper extends Mapper<LongWritable,Text,Text,IntArrayWritable> {
  
  public static int TIDS_BUFFER_SIZE = 1000000;
  private static final String ItemDelimiter = "\\s+";

  public static class Trie {
    public int id;
    public List<Integer> tids;
    public Map<Integer,Trie> children;
    
    public Trie(int id) {
      this.id = id;
      tids = new LinkedList<Integer>();
      children = new HashMap<Integer,Trie>();
    }
    
    public Trie getChild(int id) {
      Trie child = children.get(id);
      if (child == null) {
        child = new Trie(id);
        children.put(id, child);
      } else {
      }
      //System.out.println("====> child: " + child);
      return child;
    }
    
    public void addTid(int tid) {
      tids.add(tid);
      //System.out.println("=======================> tids: " + tids);
    }
  }
  
  private IntArrayWritable iaw;
  
  private Set<Integer> singletons;
  private Trie countTrie;
  
  private int phase = 1;
  
  int counter;
  int tidCounter = 0;
  int id;
  
  public IntWritable[] createIntWritableWithIdSet(int numberOfTids) {
    IntWritable[] iw = new IntWritable[numberOfTids + 2];
    iw[0] = new IntWritable(id);
    return iw;
  }
  
  @Override
  public void setup(Context context) throws IOException {
    countTrie = new Trie(-1);
    counter = 0;
    id = context.getTaskAttemptID().getTaskID().getId();
    iaw = new IntArrayWritable();
  }
  
  @Override
  public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
    String line = value.toString();
	//System.out.println("====> line: " + line);
    List<Integer> items = convertLineToSet(line, phase == 1, singletons);
    //System.out.println("====> items: " + items);
    reportItemTids(context, items);
    counter++;
  }
  
  @Override
  public void cleanup(Context context) throws IOException, InterruptedException {
    if (tidCounter != 0) {
      //doRecursiveReport(context, new StringBuilder(), countTrie);
	  recReport(context, new StringBuilder(), countTrie);
    }
  }
  
  private static Trie initializeCountTrie(Set<SortedSet<Integer>> itemsets) {
    Trie countTrie = new Trie(-1);
    for (SortedSet<Integer> itemset : itemsets) {
      Trie trie = countTrie;
      for (int item : itemset) {
        //System.out.println("====> item: " + item);
        trie = trie.getChild(item);
      }
    }
    return countTrie;
  }
  
  private void reportItemTids(Context context, List<Integer> items) throws IOException, InterruptedException {
    if (items.size() < phase) {
      return;
    }
    
    if (phase == 1) {
      for (Integer item : items) {
        countTrie.getChild(item).addTid(counter);
        tidCounter++;
      }
    } else {
      doRecursiveTidAdd(context, items, 0, countTrie);
    }
    if (tidCounter >= TIDS_BUFFER_SIZE) {
      System.out.println("Tids buffer reached, reporting " + tidCounter + " partial tids");
      doRecursiveReport(context, new StringBuilder(), countTrie);
      tidCounter = 0;
    }
  }
  
  private void doRecursiveTidAdd(Context context, List<Integer> items, int ix, Trie trie) throws IOException,
      InterruptedException {
    for (int i = ix; i < items.size(); i++) {
      Trie recTrie = trie.children.get(items.get(i));
      if (recTrie != null) {
        if (recTrie.children.isEmpty()) { //reach the leaf
          //System.out.println("=======================> is empty items.get(i): " + items.get(i));
          recTrie.addTid(counter);
          tidCounter++;
        } else {
          //System.out.println("=======================> recur items.get(i): " + items.get(i));
          doRecursiveTidAdd(context, items, i + 1, recTrie);
        }
      }
    }
  }
  private void recReport(Context context, StringBuilder builder, Trie trie) throws IOException, InterruptedException {
    int length = builder.length();
    for (Entry<Integer,Trie> entry : trie.children.entrySet()) {
      Trie recTrie = entry.getValue();
      builder.append(recTrie.id + " ");
      if (recTrie.children.isEmpty()) {
        Text key = new Text(builder.substring(0, builder.length() - 1));
		//System.out.println("====>"+" key " + key);
		//IntWritable[] iw = createIntWritableWithIdSet(recTrie.tids.size()-1);
		IntWritable[] iw = new IntWritable[recTrie.tids.size()];
        //IntWritable value = new IntWritable(recTrie.support);
        //context.write(key, value);
		int i1 = 0;
          //iw[i1++] = new IntWritable(recTrie.id);
          //System.out.println("========> key: " + key +" | " +iw[1]);
          for (int tid : recTrie.tids) {
            iw[i1++] = new IntWritable(tid);
			//System.out.println("====> "+tid );
            //System.out.println("==>"+ " " +iw[i1-1]);
          }
          iaw.set(iw);
          context.write(key, iaw);
      } else {
        recReport(context, builder, recTrie);
      }
      builder.setLength(length);
    }
  }
  private void doRecursiveReport(Context context, StringBuilder builder, Trie trie) throws IOException,
      InterruptedException {
    int length = builder.length();
    for (Trie recTrie : trie.children.values()) {
      if (recTrie != null) {
        if (recTrie.children.isEmpty()) {
          if (recTrie.tids.isEmpty()) {
            continue;
          }
          Text key = new Text(builder.substring(0, builder.length() - 1));
          IntWritable[] iw = createIntWritableWithIdSet(recTrie.tids.size());
          int i1 = 1;
          iw[i1++] = new IntWritable(recTrie.id);
          //System.out.println("========> key: " + key +" | " +iw[1]);
          for (int tid : recTrie.tids) {
            iw[i1++] = new IntWritable(tid);
            //System.out.println("==>"+ " " +iw[i1-1]);
          }
          iaw.set(iw);
          context.write(key, iaw);
          recTrie.tids.clear();
        } else {
          builder.append(recTrie.id + " ");
          doRecursiveReport(context, builder, recTrie);
        }
      }
      builder.setLength(length);
    }
  }
   private static List<Integer> convertLineToSet(String line, boolean levelOne, Set<Integer> singletons) {
    String[] itemsSplit = line.split(ItemDelimiter);
    
    List<Integer> items = new ArrayList<Integer>(itemsSplit.length);
    for (String itemString : itemsSplit) {
      Integer item = Integer.valueOf(itemString);
      if (!levelOne && !singletons.contains(item)) {
        continue;
      }
      items.add(item);
    }
    Collections.sort(items);
    return items;
  }
  
  /**
   * Creates words of length equal to length+1
   * 
   * @param tmpItemsets
   *          words of length that are merged together to form length+1 words
   * @return
   */
  private static Set<SortedSet<Integer>> createLengthPlusOneItemsets(List<Set<Integer>> tmpItemsets) {
    Set<SortedSet<Integer>> itemsets = new HashSet<SortedSet<Integer>>();
    
    int i = 0;
    ListIterator<Set<Integer>> it1 = tmpItemsets.listIterator(i);
    for (; i < tmpItemsets.size() - 1; i++) {
      Set<Integer> itemset1 = it1.next();
      ListIterator<Set<Integer>> it2 = tmpItemsets.listIterator(i + 1);
      while (it2.hasNext()) {
        SortedSet<Integer> set = new TreeSet<Integer>(itemset1);
        set.addAll(it2.next());
        if (set.size() == itemset1.size() + 1) {
          itemsets.add(set);
        }
      }
    }
    
    return itemsets;
  }
  
  /**
   * Gets the unique list of singletons in a collection of words
   * 
   * @param words
   *          the collection of words
   * @return list of unique singletons
   */
  private static Set<Integer> getSingletonsFromWords(Set<SortedSet<Integer>> words) {
    Set<Integer> singletons = new HashSet<Integer>();
    for (SortedSet<Integer> word : words) {
      singletons.addAll(word);
    }
    return singletons;
  }
  
  private static List<Set<Integer>> readItemsetsFromFile(String string) throws NumberFormatException, IOException {
    List<Set<Integer>> itemsets = new ArrayList<Set<Integer>>();
    
    String line;
    BufferedReader reader = new BufferedReader(new FileReader(string));
    while ((line = reader.readLine()) != null) {
      //System.out.println(line);
      String[] splits = line.split("\t")[0].split(" ");
      Set<Integer> set = new HashSet<Integer>(splits.length);
      for (String split : splits) {
        set.add(Integer.valueOf(split));
      }
      itemsets.add(set);
    }
    reader.close();
    
    return itemsets;
  }
}

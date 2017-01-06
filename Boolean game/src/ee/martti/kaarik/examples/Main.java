package ee.martti.kaarik.examples;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class Main {

	public static void main(String[] args) {
		Main m = new Main();
		m.start(args);	
	}
	
	private void start(String[] args) {
		if (args.length < 1)
			log("Provide an input file name as the first parameter.", true);
		
		try {
			BooleanReader r = new BooleanReader(args[0]);
			boolean[][] decks = r.getBooleans();
			
			ConsolePrinter printer = new ConsolePrinter();
			Graph graph = new Graph();
			graph.initialize();
			
			Game g = new Game(decks);
			g.addGameListener(printer);
			g.addGameListener(graph);
			g.play();
			
		} catch (FileNotFoundException e) {
			log(e.getMessage(), true);
		} catch (IOException e) {
			log(e.getMessage(), true);
		}
	}

	public void log(String errMsg, boolean exit) {
		System.err.println(errMsg);
		if (exit)
			System.exit(-1);
	}
	

}

class BooleanReader {

	private boolean[][] booleans;
	private File file;

	public BooleanReader(String fileName) throws IOException {
		//Assuming file path relative to current working directory
		this.file = new File(fileName);
	}
	
	public boolean[][] getBooleans() throws IOException {
		if (booleans == null) {
			CharSequence string = read(file);
			this.booleans = parse(string);
		}
		return booleans;
	}
	
	private CharSequence read(File f) throws IOException {
		Reader in = null;
		try {
			in = new FileReader(f);
			
			CharBuffer buf = CharBuffer.allocate(1024);
			while (in.read(buf) != -1) {
				if (buf.remaining() == 0) {
					// Buffer full, increase buffer
					CharBuffer temp = CharBuffer.allocate(buf.capacity() * 2);
					buf.flip();
					temp.append(buf);
					buf = temp;
				}
			}
			buf.flip();
			
			return buf;
			
		} finally {
			if (in != null)
				in.close();
		}
	}
	
	private boolean[][] parse(CharSequence str) {
		//Comma followed by one or more whitespace characters
		String[] hexaStrings = str.toString().split(",\\s*");
		
		//Assuming 32-bit integers
		int[] numbers = new int[hexaStrings.length];
		for (int i = 0; i < hexaStrings.length; i++)
			numbers[i] = Integer.parseInt(hexaStrings[i], 16);
		
		String[] binaryStrings = new String[numbers.length];
		for (int i = 0; i < hexaStrings.length; i++)
			binaryStrings[i] = Integer.toBinaryString(numbers[i]);
		
		boolean[][] booleans = new boolean[binaryStrings.length][];
		for (int i = 0; i < binaryStrings.length; i++) {
			booleans[i] = new boolean[binaryStrings[i].length()];
			for (int j = 0; j < booleans[i].length; j++)
				booleans[i][j] = binaryStrings[i].charAt(j) == '1';
		}
		
		return booleans;
	}
	
}

class Game implements GameListener {

	private Player[] players;
	private int[] scores;
	private boolean[] finished;
	
	private List<GameListener> listeners = new ArrayList<GameListener>();

	public Game(boolean[][] decks) {
		//Create players
		players = new Player[decks.length];
		scores = new int[players.length];
		finished = new boolean[players.length];
		//A, B, C, ... hoping not to go too far
		char name = 'A';
		for (int i = 0; i < decks.length; i++) {
			players[i] = new Player(Character.toString(name++), decks[i], this);
			scores[i] = 0;
			finished[i] = false;
		}
		
		//Assign partners
		for (int i = 0; i < players.length; i++) {
			if (i == 0)
				players[i].setLeft(players[players.length - 1]);
			else
				players[i].setLeft(players[i - 1]);
			if ((i + 1 == players.length))
				players[i].setRight(players[0]);
			else
				players[i].setRight(players[i + 1]);
		}
	}
	
	public void addGameListener(GameListener l) {
		if (!listeners.contains(l))
			listeners.add(l);
	}
	
	public synchronized void play() {
		for (Player p : players)
			p.play();
		
		String[] playerNames = new String[players.length];
		for (int i = 0; i < playerNames.length; i++)
			playerNames[i] = players[i].getName();
		for (GameListener l : listeners)
			l.gameStarted(playerNames);
		
		boolean allDone = false;
		while (!allDone)
			try {
				wait();
			} catch (InterruptedException e) {
			} finally {
				allDone = true;
				for (Boolean f : finished)
					if (!f) {
						allDone = false;
						break;
					}
			}
		
		printScores();
	}

	private void printScores() {
		for (int i = 0; i < players.length; i++)
			System.out.println(players[i].getName() + ": " + scores[i]);
	}

	@Override
	public void gameStarted(String[] playerNames) {
		for (GameListener l : listeners)
			l.gameStarted(playerNames);
	}

	@Override
	public void roundPlayed(String player, int score) {
		//Set score
		scores[indexOf(player)] += score;
		//Notify listeners
		for (GameListener l : listeners)
			l.roundPlayed(player, score);
	}

	@Override
	public synchronized void finished(String player) {
		// Mark as finished
		finished[indexOf(player)] = true;
		
		//Notify listeners
		for (GameListener l : listeners)
			l.finished(player);

		notifyAll();
	}
	
	private int indexOf(String playerName) {
		for (int i = 0; i < players.length; i++) {
			if (players[i].getName().equals(playerName))
				return i;
		}
		throw new IllegalArgumentException("No such player: " + playerName);
	}
	
}

class Player implements Runnable {
	private boolean[] deck;
	
	private GameListener listener;
	
	private Player leftPlayer;
	private Player rightPlayer;
	
	private int currentBooleanIndex;
	
	ReentrantLock lock = new ReentrantLock();

	private String name;

	private Thread t;
	
	public Player(String name, boolean[] deck, GameListener l) {
		this.deck = deck;
		this.name = name;
		listener = l;
	}
	
	public void setRight(Player player) {
		rightPlayer = player;
	}
	public void setLeft(Player player) {
		leftPlayer = player;
	}
	
	public String getName() {
		return name;
	}
	
	public void play() {
		currentBooleanIndex = 0;
		t = new Thread(this);
		t.setName(getName());
		t.start();
	}
	
	@Override
	public String toString() {
		return getName();
	}
	
	@Override
	public void run() {
		
		while (true) {
			
			boolean gotOpponentLock = false;
			Player opponent = null;
			try {
				lock.lock();
				
				if (hasFinished())
					break;
				
				opponent = deck[currentBooleanIndex] ? rightPlayer : leftPlayer;

				if (!opponent.lock.tryLock()) {
					if (lock.hasQueuedThread(opponent.t)) {
						//Opponent has already started the round, let it complete
						continue;
					}
					//Wait until we get the lock
					opponent.lock.lock();
				}
				gotOpponentLock = true;

				if (opponent.hasFinished()) {
					endGame();
					break;
				}
				
				boolean myBoolean = deck[currentBooleanIndex++];
				boolean opBoolean = opponent.playRound(myBoolean);
				
				playRound(myBoolean, opBoolean);
				
			} finally {
				if (gotOpponentLock)
					opponent.lock.unlock();
				lock.unlock();
			}
			
		}
		
		listener.finished(getName());
	}
	
	public boolean hasFinished() {
		return currentBooleanIndex == deck.length;
	}
	
	/** Remote request.	*/
	public boolean playRound(boolean opBoolean) {
		lock.lock();
		try {
			boolean myBoolean = deck[currentBooleanIndex++];
			playRound(myBoolean, opBoolean);
			return myBoolean;
		} finally {
			lock.unlock();
		}
	}
	
	private void playRound(boolean mine, boolean other) {
		int score = 0;
		if (mine == other)
			score = 1;
		else if (mine)
			score = 2;
		else
			score = 0;
		listener.roundPlayed(getName(), score);
	}
	
	private void endGame() {
		int score = 0;
		for (; currentBooleanIndex < deck.length; currentBooleanIndex++)
			score += deck[currentBooleanIndex] ? 1 : 0;
		listener.roundPlayed(getName(), score);
	}
	
	private void debug(String s) {
		System.err.println(s);
	}
	
}

interface GameListener {
	public void gameStarted(String[] playerNames);
	public void roundPlayed(String player, int score);
	public void finished(String player);
}

class ConsolePrinter implements GameListener {

	@Override
	public void gameStarted(String[] playerNames) {
		for (int i = 0; i < playerNames.length; i++) {
			System.out.print(playerNames[i]);
			if ((i + 2) < playerNames.length)
				System.out.print(", ");
			else if ((i + 1) < playerNames.length)
				System.out.print(" and ");
		}
		System.out.println(" have started a game.");
	}

	@Override
	public void roundPlayed(String player, int score) {
		System.out.println(String.format("Player %s earned %d points.", player, score));
	}

	@Override
	public void finished(String player) {
		System.out.println(player + " finished.");
	}
	
}

class Graph implements GameListener {
	
	private JPanel panel;
	
	private Map<String, List<Integer>> scores = new Hashtable<String, List<Integer>>();

	private Runnable updater = new Runnable() {
		@Override
		public void run() {
			panel.repaint();
		}
	};

	public void initialize() {
		
		JFrame frame = new JFrame("Game");
		frame.setVisible(true);
		frame.setSize(1200, 800);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});
		
		panel = new JPanel(true){
			@Override
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				paintGraph(g);
			}
		};
		frame.add(panel);
		panel.setBackground(Color.WHITE);
	}
	
	private synchronized void paintGraph(Graphics g) {
		int color = 0;
		for (List<Integer> scoreList : scores.values()) {
			color++;
			switch (color) {
			case 1:
				g.setColor(Color.BLUE);
				break;
				
			case 2:
				g.setColor(Color.GREEN);
				break;

			case 3:
				g.setColor(Color.RED);
				break;

			case 4:
				g.setColor(Color.BLACK);
				break;

			case 5:
				g.setColor(Color.ORANGE);
				break;

			case 6:
				g.setColor(Color.YELLOW);
				break;
			}
			
			int x0 = 0, y0 = 700;
			for (Integer s : scoreList) {
				g.drawLine(x0, y0, x0 + 50, 700 - s*20);
				x0 += 50;
				y0 = 700 - s*20;
			}
		}
	}

	@Override
	public void gameStarted(String[] playerNames) {
		for (int i = 0; i < playerNames.length; i++)
			scores.put(playerNames[i], new ArrayList<Integer>());
	}

	@Override
	public synchronized void roundPlayed(String player, int score) {
		List<Integer> scoreList = scores.get(player);
		if (scoreList.isEmpty())
			scoreList.add(score);
		else
			scoreList.add(scoreList.get(scoreList.size() - 1) + score);
		SwingUtilities.invokeLater(updater);
	}

	@Override
	public void finished(String player) {
	}
	
}








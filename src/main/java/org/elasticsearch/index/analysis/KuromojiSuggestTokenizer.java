package org.elasticsearch.index.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ja.JapaneseTokenizer;
import org.apache.lucene.analysis.ja.tokenattributes.ReadingAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import java.io.IOException;
import java.io.PushbackReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * A tokenizer that generates key strokes from input by utilizing {@link JapaneseTokenizer}.
 */
public class KuromojiSuggestTokenizer extends Tokenizer {

    private static final Comparator<String> LENGTH_COMPARATOR = new Comparator<String>() {
        @Override
        public int compare(String s1, String s2) {
            int res = s1.length() - s2.length();

            if (res != 0) {
                return res;
            }

            return s1.compareTo(s2);
        }
    };

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
    private final PositionLengthAttribute posLengthAtt = addAttribute(PositionLengthAttribute.class);
    private final WeightAttribute weightAtt = addAttribute(WeightAttribute.class);

    private final JapaneseTokenizer kuromoji;

    private final boolean expand;
    private final int maxExpansions;
    private final boolean edgeNGram;

    private Iterator<Keystroke> keystrokes;
    private boolean first = true; // First token or not.

    public KuromojiSuggestTokenizer(boolean expand, int maxExpansions, boolean edgeNGram) {
        this.expand = expand;
        this.maxExpansions = maxExpansions;
        this.edgeNGram = edgeNGram;

        this.kuromoji = new JapaneseTokenizer(null, false, JapaneseTokenizer.Mode.NORMAL);
    }

    @Override
    public final boolean incrementToken() throws IOException {
        if (!this.keystrokes.hasNext()) {
            return false;
        }

        clearAttributes();

        Keystroke keystroke = this.keystrokes.next();
        if (null == keystroke) return false;
        String stroke = keystroke.getKey();
        this.termAtt.append(stroke);
        this.offsetAtt.setOffset(0, stroke.length());
        this.weightAtt.setWeight(keystroke.getWeight());
        this.weightAtt.setWeights(keystroke.getWeightHistory());

        if (this.first) {
            this.posIncAtt.setPositionIncrement(1);
            this.first = false;
        } else {
            this.posIncAtt.setPositionIncrement(0);
        }

        this.posLengthAtt.setPositionLength(1);
        return true;
    }


    @Override
    public void close() throws IOException {
        super.close();
        this.kuromoji.close();
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        java.io.PushbackReader b = new PushbackReader(this.input); // TODO: figure out while we need to peek like this and remove
        this.input = b;
        this.kuromoji.setReader(this.input);
        this.kuromoji.reset();
        boolean a= true;
        int c =b.read();
        if (c==0)
        {
            a=false;
        }
        else{
            b.unread(c);
        }

        StringBuilder readingBuilder = new StringBuilder();
        StringBuilder surfaceFormBuilder = new StringBuilder();
        while (a && this.kuromoji.incrementToken()) {
            String readingFragment = this.kuromoji.getAttribute(ReadingAttribute.class).getReading();
            String surfaceFormFragment = this.kuromoji.getAttribute(CharTermAttribute.class).toString();

            if (readingFragment == null) {
                // Use surface form if kuromoji can't produce reading.
                readingFragment = surfaceFormFragment;
            }
            readingBuilder.append(readingFragment);
            surfaceFormBuilder.append(surfaceFormFragment);
        }

        // It may contain Hiragana. Convert it to Katakana.
        hiraganaToKatakana(readingBuilder);

        List<Keystroke> keyStrokes;
        if (this.expand) {
            keyStrokes = KeystrokeUtil.toKeyStrokes(readingBuilder.toString(), this.maxExpansions);
        } else {
            keyStrokes = new ArrayList<>();
            Keystroke keystroke = KeystrokeUtil.toCanonicalKeystroke(readingBuilder.toString());
            if (null!= keystroke)
                keyStrokes.add(KeystrokeUtil.toCanonicalKeystroke(readingBuilder.toString()));
        }

        // Add original input as "keystroke"
        // Kuromoji doesn't always produce correct reading. So, we use original input for matching too.
        String surfaceForm = surfaceFormBuilder.toString();
        Keystroke surfaceFormAsKeystroke = new Keystroke(surfaceForm, surfaceForm.length());
        if (!keyStrokes.contains(surfaceFormAsKeystroke)) {
            keyStrokes.add(surfaceFormAsKeystroke);
        }

        this.keystrokes = this.edgeNGram ? KeystrokeUtil.toEdgeNGrams(keyStrokes).iterator() : keyStrokes.iterator();
        this.first = true;
    }

    private void hiraganaToKatakana(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c >= 'ぁ' && c <= 'ん') {
                sb.setCharAt(i, (char)(c - 'ぁ' + 'ァ'));
            }
        }
    }


    @Override
    public void end() throws IOException {
        super.end();
        this.kuromoji.end();
    }
}

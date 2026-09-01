package io.github.suhli.datagrip.elasticsearch.plugin.language;

import org.junit.Assert;
import org.junit.Test;

public class EsRestBraceMatcherTest {
    @Test
    public void exposesObjectAndArrayPairs() {
        var pairs = EsRestBraceMatcher.INSTANCE.getPairs();
        Assert.assertEquals(2, pairs.length);
        Assert.assertEquals(EsRestTypes.LBRACE, pairs[0].getLeftBraceType());
        Assert.assertEquals(EsRestTypes.RBRACE, pairs[0].getRightBraceType());
        Assert.assertTrue(pairs[0].isStructural());
        Assert.assertEquals(EsRestTypes.LBRACKET, pairs[1].getLeftBraceType());
        Assert.assertEquals(EsRestTypes.RBRACKET, pairs[1].getRightBraceType());
        Assert.assertTrue(pairs[1].isStructural());
    }
}

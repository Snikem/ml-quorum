/*
 *  Copyright 2015+ Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package monoforest.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Text cleanup adapted from FlexNeuART StringUtils; preserves legacy BM25 preprocessing. */
public final class QueryTextUtils {
    private static final Set<String> OPERATORS = Set.of("OR", "AND", "NOT", "TO");
    private QueryTextUtils() {}
    public static String removePunct(String text) {
        return text == null ? null : text.replaceAll("\\p{Punct}+", " ");
    }
    public static String removeLuceneSpecialOps(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("[\\s\n\r\t]")) {
            if (!OPERATORS.contains(token)) tokens.add(token);
        }
        return String.join(" ", tokens);
    }
}

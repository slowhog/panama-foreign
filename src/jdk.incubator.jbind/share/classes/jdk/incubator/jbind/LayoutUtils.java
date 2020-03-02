package jdk.incubator.jbind;

import java.util.Map;
import java.util.stream.Stream;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.SequenceLayout;

public class LayoutUtils {
    private static Stream<MemoryLayout> expandAnonymousMembers(MemoryLayout layout) {
        if (layout.name().isEmpty()) {
            if (layout instanceof GroupLayout) {
                return ((GroupLayout) layout).memberLayouts().stream()
                        .flatMap(LayoutUtils::expandAnonymousMembers);
            } else {
                // Could be padding
                return Stream.empty();
            }
        } else {
            return Stream.of(layout);
        }
    }

    public static Stream<MemoryLayout> flattenGroupLayout(GroupLayout group) {
        return group.memberLayouts().stream()
                .flatMap(LayoutUtils::expandAnonymousMembers);
    }

    public static final MemoryLayout getFieldLayout(GroupLayout group, String name) {
        return flattenGroupLayout(group)
                .filter(l -> l.name().get().equals(name))
                .findAny().get();
    }

    public static Map.Entry<Integer, MemoryLayout> dimension(MemoryLayout layout) {
        int dimensions = 0;
        MemoryLayout el = layout;
        while (el instanceof SequenceLayout) {
            dimensions++;
            el = ((SequenceLayout) el).elementLayout();
        }
        return Map.entry(dimensions, el);
    }

    public static boolean isIncomplete(FunctionDescriptor function) {
        for (MemoryLayout layout : function.argumentLayouts()) {
            if (layout instanceof SequenceLayout) {
                if (((SequenceLayout) layout).elementCount().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}

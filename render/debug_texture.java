import java.util.regex.Pattern;

public class debug_texture {
    public static void main(String[] args) {
        String input = "vec4 color = texture(tex, texCoord);";
        System.out.println("Input: " + input);
        
        // Test the regex pattern
        String pattern = "\\btexture\\s*\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)";
        String replacement = "$1.sample(sampler, $2)";
        
        String result = input.replaceAll(pattern, replacement);
        System.out.println("Output: " + result);
        
        System.out.println("Contains 'texture(': " + result.contains("texture("));
        System.out.println("Contains 'tex.sample': " + result.contains("tex.sample"));
    }
}
// Invented data for the mock API (`npm run dev:mock`, e2e tests): no real household's recipes, plan or carts.

export interface MockRecipe {
  id: number;
  title: string;
  minutes: number;
  servings: number;
  source: string;
  source_kind: "nyt" | "book" | "other";
  source_title?: string;
  source_ref?: string;
  photo: string | null; // palette key for a generated SVG "photo"; "broken" = a link that 404s (shows the placeholder); null = no image
  avg: number | null;
  cooked: number;
  description?: string;
  ingredients: [number | null, string | null, string, string?][]; // qty, unit, name, prep
  steps: string[];
}

export const RECIPES: MockRecipe[] = [
  { id: 1, title: "Sheet-pan gnocchi with sausage & peppers", minutes: 35, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "gnocchi", avg: 4.8, cooked: 6,
    ingredients: [[500, "g", "potato gnocchi"], [500, "g", "mild Italian sausage"], [2, null, "bell peppers", "sliced"],
      [1, null, "red onion", "cut in wedges"], [2, "tbsp", "olive oil"], [null, null, "parmesan", "to serve"]],
    steps: ["Heat the oven to 425°F.", "Toss gnocchi, peppers and onion with oil on a sheet pan; nestle in the sausage.",
      "Roast 25 minutes, turning once, until the gnocchi are crisp.", "Finish with parmesan."] },
  { id: 2, title: "Miso-glazed salmon with sesame greens", minutes: 25, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "salmon", avg: 4.5, cooked: 4,
    ingredients: [[4, null, "salmon fillets"], [3, "tbsp", "white miso"], [1, "tbsp", "maple syrup"], [1, "tbsp", "rice vinegar"],
      [300, "g", "baby bok choy"], [1, "tbsp", "sesame seeds"]],
    steps: ["Whisk miso, maple and vinegar.", "Brush over the salmon and broil 8 minutes.", "Sauté the bok choy; scatter sesame."] },
  { id: 3, title: "Chicken tinga tacos with pickled onions", minutes: 40, servings: 4, source: "book", source_kind: "book",
    source_title: "Weeknight Mexican", source_ref: "p. 88", photo: "tacos", avg: 4.8, cooked: 5,
    ingredients: [[700, "g", "boneless skinless chicken thighs"], [2, null, "chipotles in adobo"], [1, "can", "diced tomatoes"],
      [12, null, "corn tortillas"], [1, null, "red onion", "thinly sliced"], [1, null, "lime"]],
    steps: ["Simmer the chicken with tomatoes and chipotle 20 minutes.", "Shred and return to the sauce.",
      "Quick-pickle the onion in lime juice and salt.", "Serve in warm tortillas."] },
  { id: 4, title: "Red lentil dal with spinach & garlic naan", minutes: 45, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "dal", avg: 4.0, cooked: 3,
    ingredients: [[1.5, "cup", "red lentils"], [1, "can", "coconut milk"], [1, "tbsp", "curry powder"], [142, "g", "baby spinach"],
      [4, null, "naan"]],
    steps: ["Simmer lentils with coconut milk and spices until soft.", "Stir in the spinach.", "Warm the naan with garlic butter."] },
  { id: 5, title: "Lemon orzo with peas, feta & mint", minutes: 30, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "broken", avg: 3.8, cooked: 2,
    ingredients: [[2, "cup", "orzo"], [1.5, "cup", "frozen peas"], [200, "g", "feta"], [1, null, "lemon"], [0.25, "cup", "mint", "chopped"]],
    steps: ["Cook the orzo; add peas for the last 2 minutes.", "Toss with lemon, feta and mint."] },
  { id: 6, title: "Green curry meatballs with jasmine rice", minutes: 35, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "curry", avg: null, cooked: 0,
    ingredients: [[500, "g", "ground pork"], [3, "tbsp", "green curry paste"], [1, "can", "coconut milk"], [1.5, "cup", "jasmine rice"]],
    steps: ["Roll the pork with 1 tbsp curry paste into meatballs.", "Simmer in coconut milk and the rest of the paste.", "Serve over rice."] },
  { id: 7, title: "Mushroom bourguignon", minutes: 75, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "bourguignon", avg: 4.6, cooked: 3,
    ingredients: [[900, "g", "cremini mushrooms"], [2, null, "carrots"], [1, "cup", "red wine"], [2, "tbsp", "tomato paste"]],
    steps: ["Brown the mushrooms in batches.", "Add carrots, wine and paste; simmer 45 minutes."] },
  { id: 8, title: "Halloumi grain bowls", minutes: 30, servings: 4, source: "other", source_kind: "other",
    photo: null, avg: null, cooked: 0,
    ingredients: [[250, "g", "halloumi"], [1, "cup", "farro"], [1, null, "cucumber"], [1, "cup", "cherry tomatoes"]],
    steps: ["Cook the farro.", "Sear the halloumi.", "Build bowls."] },
  { id: 9, title: "Crispy tofu with peanut noodles", minutes: 30, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "noodles", avg: 4.3, cooked: 3, ingredients: [[1, "block", "extra-firm tofu"], [250, "g", "rice noodles"], [0.33, "cup", "peanut butter"]],
    steps: ["Press and pan-fry the tofu.", "Toss noodles in peanut sauce."] },
  { id: 10, title: "Turkey chili with cornbread", minutes: 60, servings: 6, source: "book", source_kind: "book",
    source_title: "Big Pot Suppers", source_ref: "p. 41", photo: null, avg: 4.1, cooked: 4,
    ingredients: [[700, "g", "ground turkey"], [2, "can", "kidney beans"], [1, "box", "cornbread mix"]], steps: ["Brown, simmer, bake."] },
  { id: 11, title: "Shrimp fried rice", minutes: 20, servings: 4, source: "nyt", source_kind: "nyt",
    photo: "rice", avg: 4.4, cooked: 5, ingredients: [[400, "g", "shrimp"], [3, "cup", "cooked rice"], [3, null, "eggs"]], steps: ["Stir-fry."] },
  { id: 12, title: "Roast chicken with lemon potatoes", minutes: 90, servings: 4, source: "nyt", source_kind: "nyt",
    photo: null, avg: 4.9, cooked: 8, ingredients: [[1, null, "whole chicken"], [1, "kg", "yellow potatoes"], [2, null, "lemons"]],
    steps: ["Roast at 425°F about 75 minutes."] },
];

/** Plan entries per week offset from this week: [recipe, day | null, rating | null]. */
export const PLAN: Record<number, [number, number | null, number | null][]> = {
  0: [[1, 0, 5], [2, 1, 4], [3, 2, 5], [4, 3, 4], [5, 4, null], [6, 5, null], [7, null, null], [8, null, null]],
  1: [[9, 0, null], [11, 2, null], [12, 4, null]],
};

export interface MockProduct {
  code: string; name: string; brand?: string; package_size: string; price: number; unit_price: number;
  unit_price_unit: string; color: string; deal_text?: string; weighted?: boolean; price_quantity?: number; interval?: number; min_qty?: number;
}

export const PRODUCTS: MockProduct[] = [
  { code: "P1", name: "Rice Stick Noodles", brand: "Thai Kitchen", package_size: "400 g", price: 3.99, unit_price: 1.0, unit_price_unit: "100 g", color: "#E8DCC2" },
  { code: "P2", name: "Extra Firm Tofu", brand: "Sunrise", package_size: "454 g", price: 3.49, unit_price: 0.77, unit_price_unit: "100 g", color: "#F1EADA" },
  { code: "P3", name: "Smooth Peanut Butter", brand: "Kraft", package_size: "1 kg", price: 6.99, unit_price: 0.7, unit_price_unit: "100 g", color: "#C99A5B" },
  { code: "P4", name: "Raw Shrimp, Peeled & Deveined", brand: "PC", package_size: "400 g", price: 11.99, unit_price: 3.0, unit_price_unit: "100 g", color: "#F0A383", deal_text: "Save $2.00" },
  { code: "P5", name: "Large White Eggs", brand: "PC", package_size: "12 ea", price: 4.29, unit_price: 0.36, unit_price_unit: "1 ea", color: "#EFD9BB" },
  { code: "P6", name: "Whole Chicken", brand: "PC Free From", package_size: "≈ 1.8 kg", price: 7.7, unit_price: 7.7, unit_price_unit: "1 kg", color: "#EDB7A2", weighted: true, price_quantity: 1000, interval: 250, min_qty: 1000 },
  { code: "P7", name: "Yellow Potatoes", package_size: "2.27 kg", price: 4.99, unit_price: 0.22, unit_price_unit: "100 g", color: "#E5C77E" },
  { code: "P8", name: "Lemons", package_size: "1 ea", price: 0.89, unit_price: 0.89, unit_price_unit: "1 ea", color: "#F2D65C" },
  { code: "P9", name: "Jasmine Rice", brand: "Dainty", package_size: "1.6 kg", price: 7.49, unit_price: 0.47, unit_price_unit: "100 g", color: "#EDEAE0" },
  { code: "P10", name: "2% Milk", brand: "Natrel", package_size: "4 L", price: 6.99, unit_price: 0.17, unit_price_unit: "100 ml", color: "#F7F6F1" },
  { code: "P11", name: "Green Onions", package_size: "1 bunch", price: 1.29, unit_price: 1.29, unit_price_unit: "1 ea", color: "#7FA650" },
  { code: "P12", name: "Vanilla Greek Yogurt", brand: "Oikos", package_size: "750 g", price: 6.49, unit_price: 0.87, unit_price_unit: "100 g", color: "#DCE3F0" },
  { code: "P13", name: "Rice Vermicelli", brand: "Wai Wai", package_size: "454 g", price: 2.79, unit_price: 0.61, unit_price_unit: "100 g", color: "#EFE7D4" },
  { code: "P14", name: "Organic Firm Tofu", brand: "Soyganic", package_size: "350 g", price: 3.99, unit_price: 1.14, unit_price_unit: "100 g", color: "#F4EEDF" },
  { code: "P15", name: "Natural Peanut Butter", brand: "Kraft", package_size: "750 g", price: 5.99, unit_price: 0.8, unit_price_unit: "100 g", color: "#B88449" },
  { code: "P16", name: "Strawberries", package_size: "454 g", price: 4.99, unit_price: 1.1, unit_price_unit: "100 g", color: "#D9443A" },
];

/** Cart lines a build produces for next week: item key → [product code, quantity, source]. */
export const BUILD: [string, string, number | null, string | null, string, number, string][] = [
  // key, name, qty, unit, product, quantity, source
  ["rice noodles", "Rice noodles", 250, "g", "P1", 1, "memory"],
  ["tofu", "Extra-firm tofu", 1, "block", "P2", 1, "memory"],
  ["peanut butter", "Peanut butter", 0.33, "cup", "P3", 1, "memory"],
  ["shrimp", "Shrimp", 400, "g", "P4", 1, "ai"],
  ["eggs", "Eggs", 3, null, "P5", 1, "memory"],
  ["whole chicken", "Whole chicken", 1, null, "P6", 1750, "ai"],
  ["yellow potatoes", "Yellow potatoes", 1, "kg", "P7", 1, "memory"],
  ["lemon", "Lemons", 2, null, "P8", 2, "memory"],
  ["green onion", "Green onions", 4, null, "P11", 1, "ai"],
  ["milk", "2% milk", null, null, "P10", 1, "memory"],
];

/** A soft, photo-like SVG for a recipe (the mock's stand-in for a recipe image). */
export function photoSvg(kind: string): string {
  const P: Record<string, [string, string, string[]]> = {
    gnocchi: ["#3d3730", "#2c2823", ["#f0d9a2", "#ecd096", "#e8c98a", "#c2462b", "#d9732f", "#5f7d34", "#8a4a2c"]],
    salmon: ["#7f6b55", "#efe9de", ["#f08d63", "#ea8358", "#3f6e35", "#567f3b"]],
    tacos: ["#6b4a32", "#4c3424", ["#e9c27a", "#e3b86e", "#a8442a", "#82b44c", "#f4efe4"]],
    dal: ["#7a5c45", "#f3eee4", ["#e3a23b", "#d48d2b", "#3e6b2c", "#e8d6b0"]],
    curry: ["#3b3a33", "#efe9dc", ["#b9c27a", "#7a4a2a", "#83502e", "#3f7a35", "#c63b2b"]],
    bourguignon: ["#2e2a26", "#6f3324", ["#5a2f20", "#4e281b", "#c99b6a", "#8a5a3a"]],
    noodles: ["#5a6a70", "#eae6dc", ["#e8cf96", "#d9b46a", "#c8743c", "#5f8f3e", "#f3efe5"]],
    rice: ["#6c5a4a", "#f1ece1", ["#f2e6c4", "#ea9b6f", "#e8c94f", "#6e9d48", "#f6f1e4"]],
  };
  const [bg, plate, dots] = P[kind] ?? P.gnocchi;
  let seed = [...kind].reduce((a, c) => a + c.charCodeAt(0), 7);
  const rnd = () => ((seed = (seed * 9301 + 49297) % 233280) / 233280);
  const circles = Array.from({ length: 16 }, (_, i) => {
    const a = rnd() * Math.PI * 2, r = rnd() * 120;
    return `<circle cx="${300 + Math.cos(a) * r}" cy="${220 + Math.sin(a) * r * 0.8}" r="${18 + rnd() * 26}" fill="${dots[i % dots.length]}"/>`;
  }).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 440"><defs><filter id="b"><feGaussianBlur stdDeviation="9"/></filter>
<radialGradient id="v" cx="50%" cy="45%" r="70%"><stop offset="60%" stop-color="#000" stop-opacity="0"/><stop offset="100%" stop-color="#000" stop-opacity=".28"/></radialGradient></defs>
<rect width="600" height="440" fill="${bg}"/><g filter="url(#b)"><ellipse cx="300" cy="225" rx="215" ry="185" fill="${plate}"/>${circles}</g>
<rect width="600" height="440" fill="url(#v)"/></svg>`;
}

/** A packshot-like SVG for a product. */
export function productSvg(color: string): string {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120"><rect width="120" height="120" fill="#F4F1EC"/>
<rect x="34" y="22" width="52" height="76" rx="8" fill="${color}"/><rect x="34" y="50" width="52" height="16" fill="#fff" opacity=".55"/></svg>`;
}

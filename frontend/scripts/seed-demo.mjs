// Seeds the catalogue through the public API (never straight into the database)
// so the QA screenshots show exactly what a librarian would create.
// Usage: node scripts/seed-demo.mjs [baseUrl]
const baseUrl = process.argv[2] ?? 'http://127.0.0.1:8090';
const API = `${baseUrl}/api`;

const EMAIL = process.env.DEMO_EMAIL ?? 'admin@local';
const SEED_PASSWORD = process.env.DEMO_PASSWORD ?? 'ChangeMe!2026';
const NEW_PASSWORD = 'NuevaClave2026';

let cookies = new Map();

function cookieHeader() {
  return [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
}

function absorb(response) {
  const raw = response.headers.getSetCookie?.() ?? [];
  for (const entry of raw) {
    const [pair] = entry.split(';');
    const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
  }
}

async function call(method, path, body) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      Accept: 'application/json',
      Cookie: cookieHeader(),
      ...(cookies.has('XSRF-TOKEN') ? { 'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN') } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  absorb(response);
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

async function signIn() {
  await call('GET', '/auth/csrf');
  for (const candidate of [NEW_PASSWORD, SEED_PASSWORD]) {
    const login = await call('POST', '/auth/login', { email: EMAIL, password: candidate });
    if (login.status === 200) {
      if (login.body.mustChangePassword) {
        await call('PUT', '/auth/password', {
          currentPassword: candidate,
          newPassword: NEW_PASSWORD,
        });
        await call('POST', '/auth/logout');
        cookies = new Map();
        return signIn();
      }
      return true;
    }
  }
  throw new Error('no se pudo iniciar sesion');
}

const BOOKS = [
  {
    title: 'Historia del tiempo',
    isbn: '9780306406157',
    publisher: 'Crítica',
    publicationYear: 2018,
    language: 'es',
    pages: 592,
    summary:
      'Stephen Hawking explica, sin formulas y casi sin matemáticas, qué son el espacio, el tiempo\n'
      + 'y la gravedad. Un bestseller accesible que sigue siendo la mejor introducción popular a la\n'
      + 'relatividad y a los agujeros negros.',
    categories: ['Divulgación', 'Física'],
    authors: ['Stephen Hawking'],
  },
  {
    title: 'Cien años de soledad',
    isbn: '9780307474728',
    publisher: 'Vintage',
    publicationYear: 2017,
    language: 'es',
    pages: 496,
    summary:
      'La saga de los Buendía a lo largo de siete generaciones en Macondo. Novela de familia,\n'
      + 'de guerra, de acorde y de soledad.',
    categories: ['Novela', 'Realismo Mágico'],
    authors: ['Gabriel García Márquez'],
  },
  {
    title: 'Neuromante',
    isbn: '9788491058106',
    publisher: 'Grijalbo',
    publicationYear: 2019,
    language: 'es',
    pages: 336,
    categories: ['Ciencia Ficción', 'Cyberpunk'],
    authors: ['William Gibson'],
  },
  {
    title: 'Ficciones',
    subtitle: 'Los mejores cuentos',
    isbn: '9780307950925',
    publisher: 'Debolsillo',
    publicationYear: 2009,
    language: 'es',
    pages: 384,
    categories: ['Cuento', 'Premios'],
    authors: ['Jorge Luis Borges'],
  },
  {
    title: 'El nombre de la rosa',
    isbn: '9788426403568',
    publisher: 'Anagrama',
    publicationYear: 2016,
    language: 'es',
    pages: 608,
    categories: ['Novela', 'Misterio'],
    authors: ['Umberto Eco', 'Jean-Claude Carrière'],
  },
  {
    title: 'Sapiens: De animales a dioses',
    subtitle: 'Una breve historia de la humanidad',
    isbn: '9788499926223',
    publisher: 'Debolsillo',
    publicationYear: 2015,
    language: 'es',
    pages: 496,
    categories: ['Divulgación', 'Historia'],
    authors: ['Yuval Noah Harari'],
  },
  {
    title: 'Fundamentals',
    subtitle: 'Ten Exercises on the Ten Principles of Object-Oriented Design',
    isbn: '9780134494166',
    publisher: 'Prentice Hall',
    publicationYear: 2018,
    language: 'en',
    pages: 320,
    categories: ['Tecnología'],
    authors: ['Robert C. Martin'],
  },
  {
    title: 'Clean Architecture',
    isbn: '9780134494166',
    publisher: 'Prentice Hall',
    publicationYear: 2017,
    language: 'en',
    pages: 320,
    categories: ['Tecnología', 'Software'],
    authors: ['Robert C. Martin'],
  },
];

await signIn();
console.log('sesion iniciada');

let created = 0;
for (const book of BOOKS) {
  const response = await call('POST', '/catalog/books', {
    ...book,
    authors: book.authors.map((name) => ({ name, role: 'AUTOR' })),
  });
  if (response.status === 201) {
    created += 1;
  } else if (response.status === 409) {
    // Already seeded.
  } else {
    console.log(`  ${book.title}: ${response.status} ${response.body?.detail ?? ''}`);
  }
}
console.log(`libros creados: ${created}`);

const total = await call('GET', '/catalog/books?size=1');
console.log(`total en catalogo: ${total.body.totalElements}`);

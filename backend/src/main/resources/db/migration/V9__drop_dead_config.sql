-- Ronda 19: decidir la configuracion muerta.
--
-- 'inventory.barcode_prefix', 'isbn.providers' y 'library.locale' las escribio
-- V1__baseline.sql y no las leia nadie: el prefijo de codigo vive en el value
-- object, la cadena de proveedores es codigo, y el idioma de la interfaz es
-- fijo. Editar esas filas no hacia nada, que es peor que no existieran.
--
-- 'library.name' si se queda: ahora tiene una puerta de entrada y se usa en la
-- pantalla de acceso y en el resguardo de prestamos.
delete from app_config
 where key in ('inventory.barcode_prefix', 'isbn.providers', 'library.locale');
#!/usr/bin/env python3
"""Exporta o banco MySQL do Enerzā para um arquivo SQLite.

Uso (na pasta do projeto, onde fica o config.properties):
    pip install pymysql
    python exportar_sqlite.py                      # gera enerza.sqlite
    python exportar_sqlite.py -o backup.sqlite     # nome diferente
    python exportar_sqlite.py --sessoes            # inclui também as sessões ativas

Lê db.host, db.porta, db.nome, db.usuario e db.senha do config.properties.
ATENÇÃO: o arquivo gerado contém os hashes de senha dos usuários - guarde com cuidado.
"""
import argparse
import os
import sqlite3
import sys

try:
    import pymysql
except ImportError:
    sys.exit("Falta o driver: rode  pip install pymysql")

SCHEMA = """
CREATE TABLE familias (
    id        INTEGER PRIMARY KEY,
    nome      TEXT NOT NULL,
    criado_em TEXT NOT NULL
);
CREATE TABLE usuarios (
    id                INTEGER PRIMARY KEY,
    nome              TEXT NOT NULL,
    email             TEXT NOT NULL UNIQUE,
    senha_sal         BLOB NOT NULL,
    senha_hash        BLOB NOT NULL,
    criado_em         TEXT NOT NULL,
    familia_id        INTEGER REFERENCES familias(id),
    familia_origem_id INTEGER REFERENCES familias(id),
    cargo             TEXT NOT NULL DEFAULT 'admin'
);
CREATE TABLE sessoes (
    token_hash TEXT PRIMARY KEY,
    usuario_id INTEGER NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    expira_em  INTEGER NOT NULL
);
"""

TABELAS = [
    ("familias", ["id", "nome", "criado_em"]),
    ("usuarios", ["id", "nome", "email", "senha_sal", "senha_hash", "criado_em",
                  "familia_id", "familia_origem_id", "cargo"]),
]


def ler_config(caminho):
    cfg = {}
    with open(caminho, encoding="utf-8") as f:
        for linha in f:
            linha = linha.strip()
            if not linha or linha[0] in "#!" or "=" not in linha:
                continue
            k, v = linha.split("=", 1)
            cfg[k.strip()] = v.strip()
    return cfg


def converter(v):
    # datas do MySQL viram texto ISO; bytes continuam BLOB
    return v.isoformat(sep=" ") if hasattr(v, "isoformat") else v


def main():
    ap = argparse.ArgumentParser(description="Exporta o MySQL do Enerzā para SQLite")
    ap.add_argument("-o", "--saida", default="enerza.sqlite")
    ap.add_argument("-c", "--config", default="config.properties")
    ap.add_argument("--sessoes", action="store_true", help="copiar também as sessões ativas")
    a = ap.parse_args()

    cfg = ler_config(a.config) if os.path.exists(a.config) else {}
    my = pymysql.connect(
        host=cfg.get("db.host", "localhost"),
        port=int(cfg.get("db.porta", "3306")),
        user=cfg.get("db.usuario", "root"),
        password=cfg.get("db.senha", "admin"),
        database=cfg.get("db.nome", "enerza"),
        charset="utf8mb4",
    )

    if os.path.exists(a.saida):
        os.remove(a.saida)
    lite = sqlite3.connect(a.saida)
    lite.executescript(SCHEMA)

    tabelas = list(TABELAS)
    if a.sessoes:
        tabelas.append(("sessoes", ["token_hash", "usuario_id", "expira_em"]))

    with my.cursor() as cur:
        for nome, colunas in tabelas:
            cols = ", ".join(colunas)
            cur.execute(f"SELECT {cols} FROM {nome}")
            linhas = [tuple(converter(v) for v in r) for r in cur.fetchall()]
            marcas = ", ".join("?" * len(colunas))
            lite.executemany(f"INSERT INTO {nome} ({cols}) VALUES ({marcas})", linhas)
            print(f"{nome}: {len(linhas)} registro(s)")

    lite.commit()
    lite.close()
    my.close()
    print("Pronto ->", a.saida)


if __name__ == "__main__":
    main()
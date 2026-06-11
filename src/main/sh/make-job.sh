#!/bin/bash --login
#SBATCH --time=24:00:00
#SBATCH --partition=smp
#SBATCH --output=./logfile/make-%x-%j.log
#SBATCH --nodes=1                       # MATSim/prepare runs on a single node
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=12              # CPUs available to the make recipes
#SBATCH --mem=60G                       # headroom above the Makefile's -Xmx50G (JVM off-heap/GC/metaspace)
#SBATCH --job-name=make-prepare

# Run one or more make targets as a batch job, e.g.:
#   sbatch src/main/sh/make-job.sh input/before-calibration/output/prepare-100pct-with-trips-split-merged.plans.xml.gz
# Anything after the script name is forwarded to make ($@), so you can also pass
# variable overrides, e.g.:  sbatch src/main/sh/make-job.sh MEMORY=40G <target>

date
hostname

module add java/25
java -version

# The Makefile defaults to MEMORY=50G (java -Xms50G -Xmx50G); --mem above leaves room for it.
make "$@"

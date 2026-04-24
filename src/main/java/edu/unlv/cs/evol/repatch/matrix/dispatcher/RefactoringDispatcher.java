package edu.unlv.cs.evol.repatch.matrix.dispatcher;


import edu.unlv.cs.evol.repatch.refactoringObjects.RefactoringObject;
import com.intellij.openapi.project.Project;

public abstract class RefactoringDispatcher implements Dispatcher {
    Project project;
    RefactoringObject refactoringObject;
    boolean simplify;

    public void set(RefactoringObject refactoringObject, Project project, boolean simplify) {
        this.refactoringObject = refactoringObject;
        this.project = project;
        this.simplify = simplify;
    }

    public void setRefactoringObject(RefactoringObject refactoringObject) {
        this.refactoringObject = refactoringObject;
    }

    public RefactoringObject getRefactoringObject() {
        return this.refactoringObject;
    }

    public Project getProject() {
        return this.project;
    }

    public boolean isSimplify() {
        return this.simplify;
    }
}

